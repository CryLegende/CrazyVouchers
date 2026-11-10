package com.badbones69.crazyvouchers.commands.features.admin;

import com.badbones69.crazyvouchers.Methods;
import com.badbones69.crazyvouchers.api.enums.FileKeys;
import com.badbones69.crazyvouchers.api.enums.config.Messages;
import com.badbones69.crazyvouchers.api.enums.misc.PermissionKeys;
import com.badbones69.crazyvouchers.api.events.VoucherRedeemCodeEvent;
import com.badbones69.crazyvouchers.api.objects.VoucherCode;
import com.badbones69.crazyvouchers.commands.BaseCommand;
import com.badbones69.crazyvouchers.config.ConfigManager;
import com.badbones69.crazyvouchers.config.types.ConfigKeys;
import com.badbones69.crazyvouchers.network.SharedCodes;
import com.badbones69.crazyvouchers.utils.ScheduleUtils;
import com.ryderbelserion.fusion.core.api.enums.Level;
import com.ryderbelserion.fusion.paper.builders.items.ItemBuilder;
import dev.triumphteam.cmd.core.annotations.ArgName;
import dev.triumphteam.cmd.core.annotations.Command;
import dev.triumphteam.cmd.bukkit.annotation.Permission;
import dev.triumphteam.cmd.core.annotations.Suggestion;
import dev.triumphteam.cmd.core.annotations.Syntax;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import org.jetbrains.annotations.NotNull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CommandRedeem extends BaseCommand {

    @Command(value = "redeem")
    @Permission(value = "crazyvouchers.redeem", def = PermissionDefault.OP)
    @Syntax("/crazyvouchers redeem [code]")
    public void redeem(final Player player, @ArgName("code") @Suggestion("codes") String name) {
        final Location location = player.getLocation();

        final Map<String, String> placeholders = new HashMap<>();

        placeholders.put("{arg}", name);
        placeholders.put("{player}", player.getName());
        placeholders.put("{world}", location.getWorld().getName());
        placeholders.put("{x}", String.valueOf(location.x()));
        placeholders.put("{y}", String.valueOf(location.y()));
        placeholders.put("{z}", String.valueOf(location.z()));
        placeholders.put("{prefix}", ConfigManager.getConfig().getProperty(ConfigKeys.command_prefix));

        if (!this.crazyManager.isVoucherCode(name)) {
            Messages.code_unavailable.sendMessage(player, placeholders);

            return;
        }

        final VoucherCode code = this.crazyManager.getVoucherCode(name);

        if (code == null) {
            return;
        }

        final Server server = this.plugin.getServer();

        // Checking the permissions of the code.
        if (!player.isOp() && !PermissionKeys.crazyvouchers_bypass.hasPermission(player)) {
            if (code.useWhiteListPermissions()) {
                final List<String> commands = code.getWhitelistCommands();

                for (final String permission : code.getWhitelistPermissions()) {
                    if (!player.hasPermission(permission)) {
                        Messages.no_permission_to_use_voucher.sendMessage(player, placeholders);

                        Methods.dispatch(player, commands, placeholders, true);

                        return;
                    }
                }
            }

            if (code.useWhitelistWorlds()) {
                if (code.getWhitelistWorlds().contains(player.getWorld().getName().toLowerCase())) {
                    player.sendMessage(this.fusion.asComponent(player, code.getWhitelistWorldMessage(), placeholders));

                    Methods.dispatch(player, code.getWhitelistWorldCommands(), placeholders, true);

                    return;
                }
            }

            if (code.useBlacklistPermissions()) {
                final List<String> commands = code.getBlacklistCommands();

                for (final String permission : code.getBlacklistPermissions()) {
                    if (player.hasPermission(permission.toLowerCase())) {
                        player.sendMessage(this.fusion.asComponent(player, code.getBlacklistMessage(), placeholders));

                        Methods.dispatch(player, commands, placeholders, true);

                        return;
                    }
                }
            }
        }

        // Has permission to continue.
        final SharedCodes sharedCodes = this.plugin.getSharedCodes();

        if (sharedCodes != null) {
            redeemShared(sharedCodes, player, code, placeholders);

            return;
        }

        final FileConfiguration data = FileKeys.users.getConfiguration();
        final String uuid = player.getUniqueId().toString();
        // Checking if the player has used the code before.

        final String cleanName = code.getStrippedName();

        if (data.contains("Players." + uuid)) {
            if (data.contains("Players." + uuid + ".Codes." + cleanName)) {
                if (data.getString("Players." + uuid + ".Codes." + cleanName, "").equalsIgnoreCase("used")) {
                    Messages.code_used.sendMessage(player, placeholders);

                    return;
                }
            }
        }

        //Checking the limit of the code.
        if (code.useLimiter()) {
            if (data.contains("Voucher-Limit." + cleanName)) {
                if (data.getInt("Voucher-Limit." + cleanName) < 1) {
                    Messages.code_unavailable.sendMessage(player, placeholders);

                    return;
                }

                data.set("Voucher-Limit." + cleanName, (data.getInt("Voucher-Limit." + cleanName) - 1));
            } else {
                data.set("Voucher-Limit." + cleanName, (code.getLimit() - 1));
            }

            FileKeys.users.save();
        }

        // Gives the reward to the player.
        final VoucherRedeemCodeEvent event = new VoucherRedeemCodeEvent(player, code);

        server.getPluginManager().callEvent(event);

        if (!event.isCancelled()) {
            data.set("Players." + uuid + ".Codes." + cleanName, "used");

            FileKeys.users.save();

            reward(player, code, placeholders);
        }
    }

    // Same checks as above, but the claim is made in the database shared with the other servers of the network.
    private void redeemShared(@NotNull final SharedCodes sharedCodes, @NotNull final Player player, @NotNull final VoucherCode code, @NotNull final Map<String, String> placeholders) {
        final UUID uuid = player.getUniqueId();
        final String cleanName = code.getStrippedName();

        sharedCodes.claim(uuid, cleanName, code.useLimiter(), code.getLimit()).whenComplete((outcome, exception) -> ScheduleUtils.dispatch(task -> {
            if (exception != null) {
                this.fusion.log(Level.ERROR, "Could not claim the code %s for %s: %s", cleanName, player.getName(), exception.getMessage());

                Messages.code_unavailable.sendMessage(player, placeholders);

                return;
            }

            switch (outcome) {
                case ALREADY_USED -> Messages.code_used.sendMessage(player, placeholders);
                case LIMIT_REACHED -> Messages.code_unavailable.sendMessage(player, placeholders);
                case GRANTED -> {
                    if (!player.isOnline()) {
                        sharedCodes.release(uuid, cleanName);

                        return;
                    }

                    final VoucherRedeemCodeEvent event = new VoucherRedeemCodeEvent(player, code);

                    this.plugin.getServer().getPluginManager().callEvent(event);

                    if (event.isCancelled()) {
                        sharedCodes.release(uuid, cleanName);

                        return;
                    }

                    reward(player, code, placeholders);
                }
            }
        }));
    }

    private void reward(@NotNull final Player player, @NotNull final VoucherCode code, @NotNull final Map<String, String> placeholders) {
        code.dispatchCommands(player, placeholders);

        for (final ItemBuilder itemBuilder : code.getItems()) {
            Methods.addItem(player, itemBuilder.asItemStack(player));
        }

        if (code.useSounds()) {
            for (final Sound sound : code.getSounds()) {
                player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS, code.getVolume(), code.getPitch());
            }
        }

        if (code.useFireworks()) Methods.firework(player.getLocation(), code.getFireworkColors());

        final String message = code.getMessage();

        if (!message.isEmpty()) {
            player.sendMessage(this.fusion.asComponent(player, message, placeholders));
        }
    }
}