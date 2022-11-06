package dev.darealturtywurty.superturtybot.commands.core;

import com.google.common.collect.Lists;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import dev.darealturtywurty.superturtybot.core.command.CommandCategory;
import dev.darealturtywurty.superturtybot.core.command.CoreCommand;
import dev.darealturtywurty.superturtybot.database.Database;
import dev.darealturtywurty.superturtybot.database.pojos.collections.GuildConfig;
import dev.darealturtywurty.superturtybot.database.pojos.collections.UserConfig;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.bson.conversions.Bson;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class OptCommand extends CoreCommand {

    public OptCommand() {
        super(new Types(true, false, false, false));
    }

    @Override
    public List<SubcommandData> createSubcommands() {
        return List.of(new SubcommandData("in", "Opt-in to a channel.").addOption(OptionType.STRING, "channel",
                        "The channel to opt-in to.", true, true),
                new SubcommandData("out", "Opt-out of a channel.").addOption(OptionType.CHANNEL, "channel",
                        "The channel to opt-out of.", true),
                new SubcommandData("list", "List all channels that you can opt-in/out of."));
    }

    @Override
    public CommandCategory getCategory() {
        return CommandCategory.CORE;
    }

    @Override
    public String getDescription() {
        return "Opt-in/out of channels.";
    }

    @Override
    public String getName() {
        return "opt";
    }

    @Override
    public String getRichName() {
        return "Opt";
    }

    @Override
    public String getHowToUse() {
        return "/opt <in/out/list>";
    }

    @Override
    public boolean isServerOnly() {
        return true;
    }

    @Override
    public void onCommandAutoCompleteInteraction(@NotNull CommandAutoCompleteInteractionEvent event) {
        if (!event.getName().equals(getName())) return;

        if (!event.getSubcommandName().equals("in")) return;

        Guild guild = event.getGuild();
        if (guild == null) return;

        GuildConfig config = Database.getDatabase().guildConfig.find(Filters.eq("guild", guild.getIdLong())).first();
        if (config == null) {
            event.replyChoices().queue();
            return;
        }

        Bson userFilter = Filters.and(Filters.eq("user", event.getUser().getIdLong()),
                Filters.eq("guild", guild.getIdLong()));
        UserConfig userConfig = Database.getDatabase().userConfig.find(userFilter).first();
        if (userConfig == null) {
            userConfig = new UserConfig(guild.getIdLong(), event.getUser().getIdLong());
            Database.getDatabase().userConfig.insertOne(userConfig);
        }

        List<Long> userChannels = userConfig.getOptInChannels();

        event.replyChoices(GuildConfig.getChannels(config.getOptInChannels()).stream()
                .filter(channel -> !userChannels.contains(channel))
                .map(channel -> guild.getChannels().stream().filter(guildChannel -> guildChannel.getIdLong() == channel)
                        .findFirst().orElse(null)).filter(channel -> channel != null).map(GuildChannel::getName)
                .map(channel -> new Command.Choice(channel, channel)).toList()).queue();
    }

    @Override
    protected void runSlash(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        if (!event.isFromGuild() || guild == null) {
            reply(event, "❌ You must be in a server to use this command!", false, true);
            return;
        }

        final String subcommand = event.getSubcommandName();
        if (subcommand == null) {
            reply(event, "❌ You must specify a subcommand!", false, true);
            return;
        }

        GuildConfig config = Database.getDatabase().guildConfig.find(Filters.eq("guild", guild.getIdLong())).first();
        if (config == null) {
            reply(event, "❌ This server is not configured!", false, true);
            return;
        }

        List<Long> channels = GuildConfig.getChannels(config.getOptInChannels());
        if (channels.isEmpty()) {
            reply(event, "❌ This server has no opt-in channels!", false, true);
            return;
        }

        if (subcommand.equals("in")) {
            String channelStr = event.getOption("channel").getAsString();
            if (channelStr == null || channelStr.isBlank()) {
                reply(event, "❌ You must specify a channel!", false, true);
                return;
            }

            StandardGuildChannel channel = (StandardGuildChannel) guild.getChannels(true).stream()
                    .filter(c -> c.getName()
                            .equals(channelStr) && c.getType() != ChannelType.CATEGORY && c.getType() != ChannelType.GUILD_NEWS_THREAD && c.getType() != ChannelType.GUILD_PRIVATE_THREAD && c.getType() != ChannelType.GUILD_PUBLIC_THREAD && c.getType() != ChannelType.PRIVATE && c.getType() != ChannelType.GROUP)
                    .findFirst().orElse(null);
            if (channel == null) {
                reply(event, "❌ That channel does not exist!", false, true);
                return;
            }

            if (!channels.contains(channel.getIdLong())) {
                reply(event, "❌ This channel is not available to be opted-into!", false, true);
                return;
            }

            Bson userFilter = Filters.and(Filters.eq("user", event.getUser().getIdLong()),
                    Filters.eq("guild", guild.getIdLong()));
            UserConfig userConfig = Database.getDatabase().userConfig.find(userFilter).first();
            if (userConfig == null) {
                userConfig = new UserConfig(guild.getIdLong(), event.getUser().getIdLong());
                Database.getDatabase().userConfig.insertOne(userConfig);
            }

            List<Long> userChannels = userConfig.getOptInChannels();
            if (userChannels.contains(channel.getIdLong())) {
                reply(event, "❌ You are already opted-into this channel!", false, true);
                return;
            }

            userChannels.add(channel.getIdLong());
            userConfig.setOptInChannels(userChannels);
            Database.getDatabase().userConfig.updateOne(userFilter, Updates.set("optInChannels", userChannels));

            List<Permission> permissions = Lists.newArrayList(Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY);
            if (channel.getType().isAudio()) {
                permissions.add(Permission.VOICE_CONNECT);
                permissions.add(Permission.VOICE_USE_VAD);
            }

            channel.upsertPermissionOverride(event.getMember()).setAllowed(permissions).queue();

            reply(event, "✅ You have opted-in to " + channel.getAsMention() + "!");
        } else if (subcommand.equals("out")) {
            OptionMapping channelOption = event.getOption("channel");
            if (channelOption != null && (!channelOption.getChannelType().isGuild() || channelOption.getChannelType()
                    .isThread())) {
                reply(event, "❌ You must specify a text or voice channel!", false, true);
                return;
            }

            StandardGuildChannel channel = channelOption.getAsChannel().asStandardGuildChannel();
            if (channel.getGuild().getIdLong() != guild.getIdLong()) {
                reply(event, "❌ You must specify a channel in this server!", false, true);
                return;
            }

            if (!channels.contains(channel.getIdLong())) {
                reply(event, "❌ This channel is not available to be opted-out of!", false, true);
                return;
            }

            Bson userFilter = Filters.and(Filters.eq("user", event.getUser().getIdLong()),
                    Filters.eq("guild", guild.getIdLong()));
            UserConfig userConfig = Database.getDatabase().userConfig.find(userFilter).first();
            if (userConfig == null) {
                userConfig = new UserConfig(guild.getIdLong(), event.getUser().getIdLong());
                Database.getDatabase().userConfig.insertOne(userConfig);
            }

            List<Long> userChannels = userConfig.getOptInChannels();
            if (!userChannels.contains(channel.getIdLong())) {
                reply(event, "❌ You are not opted-into this channel!", false, true);
                return;
            }

            userChannels.remove(channel.getIdLong());
            userConfig.setOptInChannels(userChannels);
            Database.getDatabase().userConfig.updateOne(userFilter, Updates.set("optInChannels", userChannels));

            List<Permission> permissions = Lists.newArrayList(Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY);
            if (channel.getType().isAudio()) {
                permissions.add(Permission.VOICE_CONNECT);
                permissions.add(Permission.VOICE_USE_VAD);
            }

            channel.upsertPermissionOverride(event.getMember()).setDenied(permissions).queue();

            reply(event, "✅ You have opted-out of `#" + channel.getName() + "`!");
        } else if (subcommand.equals("list")) {
            var builder = new StringBuilder("Available channels to opt-in/out of:\n");
            for (long channel : channels) {
                GuildChannel guildChannel = guild.getChannelById(StandardGuildChannel.class, channel);
                if (guildChannel == null) {
                    continue;
                }

                builder.append("- `#").append(guildChannel.getName()).append("` (")
                        .append(guildChannel.getType() == ChannelType.TEXT ? "Text" : "Voice").append(")\n");
            }

            reply(event, builder.toString());
        }
    }
}