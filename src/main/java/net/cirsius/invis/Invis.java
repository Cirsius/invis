package net.cirsius.invis;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

public final class Invis extends JavaPlugin implements Listener {
    private final Set<UUID> invisible = new HashSet<>();
    private final Set<Player> active = new HashSet<>();
    private File stateFile;
    private BukkitTask upkeep;
    private Method setInvisible;
    private Method getHandle;

    @Override
    public void onEnable() {
        try {
            stateFile = new File(getDataFolder(), "players.yml");
            YamlConfiguration state = new YamlConfiguration();
            if (stateFile.exists()) state.load(stateFile);
            for (String id : state.getStringList("invisible")) invisible.add(UUID.fromString(id));
            try {
                setInvisible = org.bukkit.entity.Entity.class.getMethod("setInvisible", boolean.class);
            } catch (NoSuchMethodException legacy) {
                Class<?> craftPlayer = Class.forName(getServer().getClass().getPackage().getName() + ".entity.CraftPlayer");
                getHandle = craftPlayer.getMethod("getHandle");
                setInvisible = getHandle.getReturnType().getMethod("setInvisible", boolean.class);
            }
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException | ReflectiveOperationException error) {
            getLogger().severe("couldn't load players.yml: " + error.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : getServer().getOnlinePlayers()) refresh(player);
    }

    @Override
    public void onDisable() {
        if (upkeep != null) upkeep.cancel();
        for (Player target : active) update(target, false);
        active.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§conly players can use /invis");
            return true;
        }
        Player player = (Player) sender;
        if (args.length != 0) {
            player.sendMessage("§euse /invis");
            return true;
        }
        boolean hidden = !invisible.contains(player.getUniqueId());
        if (hidden && !player.hasPermission("invis.use")) {
            player.sendMessage("§cyou don't have permission to use this");
            return true;
        }
        YamlConfiguration state = new YamlConfiguration();
        List<String> ids = new ArrayList<>(invisible.size() + 1);
        for (UUID id : invisible) {
            if (!id.equals(player.getUniqueId())) ids.add(id.toString());
        }
        if (hidden) ids.add(player.getUniqueId().toString());
        Collections.sort(ids);
        state.set("invisible", ids);
        try {
            state.save(stateFile);
        } catch (IOException error) {
            getLogger().severe("couldn't save players.yml: " + error.getMessage());
            player.sendMessage("§ccouldn't save invisibility, try again");
            return true;
        }
        if (hidden) {
            invisible.add(player.getUniqueId());
            activate(player);
        } else {
            invisible.remove(player.getUniqueId());
            deactivate(player);
            update(player, false);
        }
        player.sendMessage(hidden ? "§ayou're invisible" : "§ayou're visible");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
        refreshNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        deactivate(event.getPlayer());
        event.getPlayer().removeMetadata("invis.active", this);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        refreshNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        refresh(event.getPlayer());
        refreshNextTick(event.getPlayer());
    }

    private void refreshNextTick(Player player) {
        getServer().getScheduler().runTask(this, () -> {
            if (player.isOnline()) refresh(player);
        });
    }

    private void refresh(Player player) {
        if (invisible.contains(player.getUniqueId())) activate(player);
    }

    private void activate(Player player) {
        if (active.add(player)) player.setMetadata("invis.active", new FixedMetadataValue(this, true));
        update(player, true);
        if (upkeep == null) {
            upkeep = getServer().getScheduler().runTaskTimer(this, () -> {
                for (Player target : active) update(target, true);
            }, 1L, 1L);
        }
    }

    private void deactivate(Player player) {
        active.remove(player);
        if (active.isEmpty() && upkeep != null) {
            upkeep.cancel();
            upkeep = null;
        }
    }

    private void update(Player target, boolean hidden) {
        PotionEffect effect = hidden ? null : target.getActivePotionEffects().stream()
                .filter(e -> e.getType().equals(PotionEffectType.INVISIBILITY)).findFirst().orElse(null);
        try {
            setInvisible.invoke(getHandle == null ? target : getHandle.invoke(target), hidden);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("couldn't change invisibility", error);
        }
        if (!hidden) {
            target.removeMetadata("invis.active", this);
            if (effect != null) {
                target.removePotionEffect(PotionEffectType.INVISIBILITY);
                target.addPotionEffect(effect, true);
            }
        }
    }

}
