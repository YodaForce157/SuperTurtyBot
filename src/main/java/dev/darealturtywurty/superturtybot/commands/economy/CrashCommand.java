package dev.darealturtywurty.superturtybot.commands.economy;

import dev.darealturtywurty.superturtybot.TurtyBot;
import dev.darealturtywurty.superturtybot.core.util.MathUtils;
import dev.darealturtywurty.superturtybot.database.pojos.collections.Economy;
import dev.darealturtywurty.superturtybot.database.pojos.collections.GuildData;
import dev.darealturtywurty.superturtybot.modules.economy.EconomyManager;
import lombok.Data;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

public class CrashCommand extends EconomyCommand {
    private static final ScheduledExecutorService EXECUTOR = Executors.newScheduledThreadPool(10);
    private static final Map<Long, List<Game>> GAMES = new HashMap<>();

    @Override
    public List<OptionData> createOptions() {
        return List.of(new OptionData(OptionType.INTEGER, "amount", "The amount of money to bet.", true).setMinValue(1));
    }

    @Override
    public String getDescription() {
        return "Bet money in a game of Crash!";
    }

    @Override
    public String getName() {
        return "crash";
    }

    @Override
    public String getRichName() {
        return "Crash";
    }

    @Override
    public String getHowToUse() {
        return "/crash <amount>";
    }

    @Override
    protected void runSlash(SlashCommandInteractionEvent event, Guild guild, GuildData config) {
        int amount = event.getOption("amount", 1, OptionMapping::getAsInt);
        if (amount < 1) {
            event.getHook().editOriginal("❌ You must bet at least %s1!".formatted(config.getEconomyCurrency())).queue();
            return;
        }

        Economy account = EconomyManager.getOrCreateAccount(guild, event.getUser());
        if (amount > account.getWallet()) {
            event.getHook().editOriginal("❌ You cannot bet more than you have in your wallet!").queue();
            return;
        }

        final List<Game> games = GAMES.computeIfAbsent(guild.getIdLong(), ignored -> new ArrayList<>());
        if(!games.isEmpty() && games.stream().anyMatch(game -> game.getGuild() == guild.getIdLong() && game.getUser() == event.getUser().getIdLong())) {
            event.getHook().editOriginal("❌ You are already in a game of Crash!").queue();
            return;
        }

        EconomyManager.removeMoney(account, amount, false);
        EconomyManager.updateAccount(account);

        event.getHook().editOriginal("✅ You have bet %s%d!".formatted(config.getEconomyCurrency(), amount)).queue(message -> {
            message.createThreadChannel(event.getUser().getName() + "'s Crash Game").queue(thread -> {
                thread.addThreadMember(event.getUser()).queue();
                thread.sendMessage(("""
                        You have bet %s%d! The multiplier has started at 1.0x!

                        It will increase by a random amount every 2 seconds, however, it will crash at a random point between 1.0x and 10.0x!
                                                
                        Good luck!""").formatted(config.getEconomyCurrency(), amount)).queue(ignored -> {
                    var game = new Game(guild.getIdLong(), thread.getIdLong(), event.getUser().getIdLong(), amount);
                    games.add(game);

                    TurtyBot.EVENT_WAITER.builder(MessageReceivedEvent.class)
                            .condition(msgEvent -> msgEvent.isFromGuild()
                                    && msgEvent.getGuild().getIdLong() == guild.getIdLong()
                                    && msgEvent.getChannel().getIdLong() == thread.getIdLong()
                                    && msgEvent.getAuthor().getIdLong() == event.getUser().getIdLong()
                                    && msgEvent.getMessage().getContentRaw().equalsIgnoreCase("cashout"))
                            .success(msgEvent -> game.cashout(event.getJDA(), config, account))
                            .failure(() -> game.close(thread))
                            .timeout(10, TimeUnit.MINUTES)
                            .build();

                    game.start(event.getJDA(), config, account);
                });
            });
        });
    }

    @Data
    public static class Game {
        private final long guild;
        private final long channel;
        private final long user;
        private final int amount;

        private double multiplier = 1.0;
        private ScheduledFuture<?> future;

        public Game(long guild, long channel, long user, int amount) {
            this.guild = guild;
            this.channel = channel;
            this.user = user;
            this.amount = amount;
        }

        public void start(final JDA jda, final GuildData config, final Economy account) {
            if(this.future != null) {
                this.future.cancel(true);
            }

            int crashChance = ThreadLocalRandom.current().nextInt(5, 15);
            this.future = EXECUTOR.scheduleAtFixedRate(
                    () -> tick(jda, config, account, crashChance),
                    0,
                    2,
                    TimeUnit.SECONDS);
        }

        private void tick(final JDA jda, final GuildData config, final Economy account, final int crashChance) {
            Guild guild = jda.getGuildById(this.guild);
            if (guild == null)
                return;

            ThreadChannel thread = guild.getThreadChannelById(this.channel);
            if (thread == null)
                return;

            thread.sendMessage("The multiplier is now at %s!".formatted(stringifyMultiplier(multiplier))).queue();

            if (multiplier >= 10.0) {
                cashout(jda, config, account);
                return;
            }

            multiplier += getMultiplier();

            boolean crashed = ThreadLocalRandom.current().nextInt(crashChance) == 0;
            if (crashed) {
                thread.sendMessage("The multiplier has crashed at %s! You have lost %s%d!"
                        .formatted(stringifyMultiplier(multiplier), config.getEconomyCurrency(), this.amount)).queue(
                                ignored -> close(thread));
                EconomyManager.betLoss(account, this.amount);
                EconomyManager.updateAccount(account);
            }
        }

        public void cashout(JDA jda, GuildData config, Economy account) {
            Guild guild = jda.getGuildById(this.guild);
            if (guild == null)
                return;

            ThreadChannel thread = guild.getThreadChannelById(this.channel);
            if (thread == null)
                return;

            int amount = (int) (this.amount * MathUtils.clamp(multiplier, 1.0, 10.0));
            if(multiplier >= 10) {
                thread.sendMessage("The multiplier has reached 10.0x! You have won %s%d!"
                        .formatted(config.getEconomyCurrency(), amount)).queue(
                                ignored -> close(thread));
            } else {
                thread.sendMessage("You have cashed out at %s! You have won %s%d!"
                        .formatted(stringifyMultiplier(multiplier), config.getEconomyCurrency(), amount)).queue(
                                ignored -> close(thread));
            }

            EconomyManager.addMoney(account, amount);
            EconomyManager.betWin(account, amount);
            EconomyManager.updateAccount(account);
        }

        private void close(ThreadChannel thread) {
            this.future.cancel(true);
            thread.getManager().setArchived(true).setLocked(true).queue();
            List<Game> games = GAMES.computeIfAbsent(this.guild, ignored -> new ArrayList<>());
            games.remove(this);
        }

        /**
         * Gets a random multiplier between 0 and 1 truncated to 2 decimal places
         *
         * @return The multiplier
         */
        private static double getMultiplier() {
            return MathUtils.clamp(Math.floor(ThreadLocalRandom.current().nextDouble(0, 0.2) * 100) / 100.0D, 0.01, 1);
        }

        /**
         * Converts a multiplier to a string
         *
         * @param multiplier The multiplier to convert
         * @return The multiplier as a string
         */
        private static String stringifyMultiplier(double multiplier) {
            return String.format("%.2fx", multiplier);
        }
    }
}
