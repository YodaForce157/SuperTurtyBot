package io.github.darealturtywurty.superturtybot.commands.core.config;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import org.apache.commons.text.WordUtils;
import org.bson.conversions.Bson;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;

import io.github.darealturtywurty.superturtybot.core.command.CommandCategory;
import io.github.darealturtywurty.superturtybot.core.command.CoreCommand;
import io.github.darealturtywurty.superturtybot.database.Database;
import io.github.darealturtywurty.superturtybot.database.pojos.collections.UserConfig;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

public class UserConfigCommand extends CoreCommand {
    public UserConfigCommand() {
        super(new Types(true, false, false, false));
    }

    //@formatter:off
    @Override
    public List<SubcommandData> createSubcommands() {
        return List.of(
            new SubcommandData("get", "Gets the current user config")
                .addOption(OptionType.STRING, "key","The data key to get", false, true),
            new SubcommandData("set", "Sets a value into the user config")
                .addOption(OptionType.STRING, "key", "The data key to change", true, true)
                .addOption(OptionType.STRING, "value", "The piece of data to assign to this key", true, true));
    }
    //@formatter:on

    @Override
    public CommandCategory getCategory() {
        return CommandCategory.CORE;
    }

    @Override
    public String getDescription() {
        return "Sets up your user config";
    }
    
    @Override
    public String getName() {
        return "userconfig";
    }
    
    @Override
    public String getRichName() {
        return "User Config";
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if (!event.isFromGuild() || !event.getName().equals(getName()))
            return;
        
        final String term = event.getFocusedOption().getValue();
        
        final List<String> keys = UserConfigRegistry.USER_CONFIG_OPTIONS.getRegistry().entrySet().stream()
            .map(Entry::getValue).map(UserConfigOption::getSaveName).filter(key -> key.contains(term)).limit(25)
            .toList();
        event.replyChoiceStrings(keys).queue();
    }
    
    @Override
    protected void runSlash(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild() || event.getUser().getIdLong() != event.getGuild().getOwnerIdLong())
            return;

        final String subcommand = event.getSubcommandName();
        if ("get".equalsIgnoreCase(subcommand)) {
            final String key = event.getOption("key", null, OptionMapping::getAsString);

            final Bson filter = getFilter(event.getGuild(), event.getUser());
            final UserConfig config = get(filter, event.getGuild(), event.getUser());

            if (key == null) {
                // Get all data
                final Map<String, Object> configValues = new HashMap<>();
                UserConfigRegistry.USER_CONFIG_OPTIONS.getRegistry().entrySet().stream().map(Entry::getValue).forEach(
                    option -> configValues.put(option.getRichName(), option.getValueFromConfig().apply(config)));
                final var embed = new EmbedBuilder();
                configValues.forEach((name, value) -> embed.appendDescription("**" + name + "**: `" + value + "`\n"));
                embed.setFooter("For server: " + event.getGuild().getName(), event.getGuild().getIconUrl());
                embed.setColor(event.getMember().getColorRaw());
                embed.setTitle(
                    "User Config for: " + event.getUser().getName() + "#" + event.getUser().getDiscriminator());
                embed.setTimestamp(Instant.now());
                embed.setThumbnail(event.getMember().getEffectiveAvatarUrl());
                reply(event, embed);
                return;
            }

            // Get data by the given key
            final String copyKey = key.trim();
            final Optional<UserConfigOption> found = UserConfigRegistry.USER_CONFIG_OPTIONS.getRegistry().entrySet()
                .stream().filter(entry -> entry.getValue().getSaveName().equals(copyKey)).map(Entry::getValue)
                .findFirst();
            
            if (found.isEmpty()) {
                reply(event, "❌ `" + key + "` is not a valid option for your user config!", false, true);
                return;
            }
            
            final UserConfigOption option = found.get();
            final Object value = option.getValueFromConfig().apply(config);
            reply(event, "✅ The value assigned to `" + option.getRichName() + "` is `" + value + "`!");
        }

        if ("set".equalsIgnoreCase(subcommand)) {
            final String key = event.getOption("key", "", OptionMapping::getAsString);
            final String value = event.getOption("value", "", OptionMapping::getAsString);

            final Bson filter = getFilter(event.getGuild(), event.getUser());
            final UserConfig config = get(filter, event.getGuild(), event.getUser());
            
            final Optional<Entry<String, UserConfigOption>> found = UserConfigRegistry.USER_CONFIG_OPTIONS.getRegistry()
                .entrySet().stream().filter(entry -> entry.getValue().getSaveName().equals(key)).findFirst();
            if (found.isEmpty()) {
                reply(event, "❌ `" + key + "` is not a valid option for your user config!", false, true);
                return;
            }

            final UserConfigOption option = found.get().getValue();
            if (Boolean.FALSE.equals(option.getDataType().validator.apply(value))) {
                reply(event,
                    "❌ `" + value + "` is not the right data type! `" + option.getRichName() + "` requires a `"
                        + WordUtils.capitalize(option.getDataType().name().toLowerCase().replace("_", " ")) + "`!",
                    false, true);
                return;
            }

            if (!option.validate(event, value)) {
                reply(event, "❌ `" + value + "` is not a valid input for `" + option.getRichName() + "`!", false, true);
                return;
            }
            
            option.serialize(config, value);
            final Bson update = Updates.set(option.getSaveName(), option.getValueFromConfig().apply(config));
            final UpdateResult result = Database.getDatabase().guildConfig.updateOne(filter, update);
            if (result.getModifiedCount() > 0) {
                reply(event, "✅ `" + option.getRichName() + "` has successfully been set to `" + value + "`!");
                return;
            }

            reply(event, "❌ `" + value + "` was already the value assigned to `" + option.getRichName() + "`!", false,
                true);
        }
    }

    private static UserConfig get(Bson filter, Guild guild, User user) {
        UserConfig found = Database.getDatabase().userConfig.find(filter).first();
        if (found == null) {
            found = new UserConfig(guild.getIdLong(), user.getIdLong());
            Database.getDatabase().userConfig.insertOne(found);
        }

        return found;
    }

    private static Bson getFilter(Guild guild, User user) {
        return Filters.and(Filters.eq("guild", guild.getIdLong()), Filters.eq("user", user.getIdLong()));
    }
}
