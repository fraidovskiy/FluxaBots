package ru.serverbots;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class ServerBots extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private ProtocolManager protocol;
    private final Map<String, UUID> bots = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            getLogger().severe("ProtocolLib is required. ServerBots will be disabled.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        protocol = ProtocolLibrary.getProtocolManager();
        loadBots();

        Objects.requireNonNull(getCommand("bots")).setExecutor(this);
        Objects.requireNonNull(getCommand("bots")).setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTask(this, () -> {
            for (Player viewer : Bukkit.getOnlinePlayers()) sendAllBots(viewer);
        });
        getLogger().info("ServerBots enabled. Loaded " + bots.size() + " TAB bot(s).");
    }

    @Override
    public void onDisable() {
        if (protocol != null) {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                for (String name : bots.keySet()) removeTabEntry(viewer, name, bots.get(name));
            }
        }
    }

    private void loadBots() {
        bots.clear();
        for (String name : getConfig().getStringList("bots")) {
            if (validName(name)) bots.put(name.toLowerCase(Locale.ROOT), UUID.nameUUIDFromBytes(
                    ("ServerBots:" + name.toLowerCase(Locale.ROOT)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            else getLogger().warning("Skipped invalid bot name in config: " + name);
        }
    }

    private void saveBots() {
        getConfig().set("bots", bots.keySet().stream().sorted().collect(Collectors.toList()));
        saveConfig();
    }

    private boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{1,16}");
    }

    private void sendAllBots(Player viewer) {
        for (String key : bots.keySet()) addTabEntry(viewer, displayName(key), bots.get(key));
    }

    private String displayName(String key) {
        // Preserve the configured casing from config/command by finding the stored display form.
        return getConfig().getString("names." + key, key);
    }

    private void addTabEntry(Player viewer, String name, UUID uuid) {
        try {
            PacketContainer packet = protocol.createPacket(PacketType.Play.Server.PLAYER_INFO);
            // Keep the packet's final EnumSet instance; mutate it instead of replacing it.
            Set<EnumWrappers.PlayerInfoAction> actions = packet.getPlayerInfoActions().read(0);
            actions.clear();
            actions.addAll(EnumSet.of(
                    EnumWrappers.PlayerInfoAction.ADD_PLAYER,
                    EnumWrappers.PlayerInfoAction.UPDATE_LISTED,
                    EnumWrappers.PlayerInfoAction.UPDATE_LATENCY,
                    EnumWrappers.PlayerInfoAction.UPDATE_GAME_MODE,
                    EnumWrappers.PlayerInfoAction.UPDATE_DISPLAY_NAME
            ));
            WrappedGameProfile profile = new WrappedGameProfile(uuid, name);
            PlayerInfoData data = new PlayerInfoData(
                    uuid, 0, true, EnumWrappers.NativeGameMode.SURVIVAL,
                    profile, WrappedChatComponent.fromText(name)
            );
            packet.getPlayerInfoDataLists().write(1, Collections.singletonList(data));
            protocol.sendServerPacket(viewer, packet);
        } catch (Exception ex) {
            getLogger().warning("Could not add TAB entry '" + name + "': " + ex.getMessage());
        }
    }

    private void removeTabEntry(Player viewer, String name, UUID uuid) {
        try {
            PacketContainer packet = protocol.createPacket(PacketType.Play.Server.PLAYER_INFO);
            // Keep the packet's final EnumSet instance; mutate it in place.
            Set<EnumWrappers.PlayerInfoAction> actions = packet.getPlayerInfoActions().read(0);
            actions.clear();
            actions.add(EnumWrappers.PlayerInfoAction.REMOVE_PLAYER);
            WrappedGameProfile profile = new WrappedGameProfile(uuid, name);
            PlayerInfoData data = new PlayerInfoData(
                    uuid, 0, true, EnumWrappers.NativeGameMode.SURVIVAL,
                    profile, WrappedChatComponent.fromText(name)
            );
            packet.getPlayerInfoDataLists().write(1, Collections.singletonList(data));
            protocol.sendServerPacket(viewer, packet);
        } catch (Exception ex) {
            getLogger().warning("Could not remove TAB entry '" + name + "': " + ex.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> sendAllBots(event.getPlayer()), 10L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        if (!sender.hasPermission("serverbots.admin")) {
            sender.sendMessage("§cНедостаточно прав.");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (args.length != 2 || !validName(args[1])) {
                    sender.sendMessage("§cИспользование: /bots add <ник> (1-16 символов: A-Z, 0-9, _)");
                    return true;
                }
                String name = args[1];
                String key = name.toLowerCase(Locale.ROOT);
                if (bots.containsKey(key)) {
                    sender.sendMessage("§eБот с таким ником уже существует.");
                    return true;
                }
                UUID uuid = UUID.nameUUIDFromBytes(("ServerBots:" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                bots.put(key, uuid);
                getConfig().set("names." + key, name);
                saveBots();
                for (Player viewer : Bukkit.getOnlinePlayers()) addTabEntry(viewer, name, uuid);
                sender.sendMessage("§aTAB-бот §f" + name + " §aдобавлен.");
                return true;
            }
            case "remove" -> {
                if (args.length != 2) {
                    sender.sendMessage("§cИспользование: /bots remove <ник>");
                    return true;
                }
                String key = args[1].toLowerCase(Locale.ROOT);
                UUID uuid = bots.remove(key);
                if (uuid == null) {
                    sender.sendMessage("§eБот не найден.");
                    return true;
                }
                String name = displayName(key);
                for (Player viewer : Bukkit.getOnlinePlayers()) removeTabEntry(viewer, name, uuid);
                getConfig().set("names." + key, null);
                saveBots();
                sender.sendMessage("§aБот §f" + name + " §aудалён.");
                return true;
            }
            case "rename" -> {
                if (args.length != 3 || !validName(args[2])) {
                    sender.sendMessage("§cИспользование: /bots rename <старый_ник> <новый_ник>");
                    return true;
                }
                String oldKey = args[1].toLowerCase(Locale.ROOT);
                String newName = args[2];
                String newKey = newName.toLowerCase(Locale.ROOT);
                UUID uuid = bots.get(oldKey);
                if (uuid == null) {
                    sender.sendMessage("§eИсходный бот не найден.");
                    return true;
                }
                if (!oldKey.equals(newKey) && bots.containsKey(newKey)) {
                    sender.sendMessage("§eНовый ник уже занят другим ботом.");
                    return true;
                }
                String oldName = displayName(oldKey);
                for (Player viewer : Bukkit.getOnlinePlayers()) removeTabEntry(viewer, oldName, uuid);
                bots.remove(oldKey);
                bots.put(newKey, uuid);
                getConfig().set("names." + oldKey, null);
                getConfig().set("names." + newKey, newName);
                saveBots();
                for (Player viewer : Bukkit.getOnlinePlayers()) addTabEntry(viewer, newName, uuid);
                sender.sendMessage("§aНик изменён: §f" + oldName + " §a→ §f" + newName);
                return true;
            }
            case "list" -> {
                sender.sendMessage("§6ServerBots §7(" + bots.size() + "):");
                if (bots.isEmpty()) sender.sendMessage("§7Список пуст.");
                else bots.keySet().stream().sorted().forEach(k -> sender.sendMessage("§8- §f" + displayName(k)));
                return true;
            }
            case "clear" -> {
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    for (String key : new ArrayList<>(bots.keySet())) {
                        removeTabEntry(viewer, displayName(key), bots.get(key));
                    }
                }
                bots.clear();
                getConfig().set("bots", Collections.emptyList());
                getConfig().set("names", null);
                saveConfig();
                sender.sendMessage("§aВсе TAB-боты удалены.");
                return true;
            }
            case "reload" -> {
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    for (String key : bots.keySet()) removeTabEntry(viewer, displayName(key), bots.get(key));
                }
                reloadConfig();
                loadBots();
                for (Player viewer : Bukkit.getOnlinePlayers()) sendAllBots(viewer);
                sender.sendMessage("§aКонфигурация ServerBots перезагружена.");
                return true;
            }
            case "status" -> {
                sender.sendMessage("§aServerBots включён. TAB-ботов: §f" + bots.size());
                sender.sendMessage("§7Ядро: Spigot 1.21.4 | ProtocolLib 5.4.0");
                return true;
            }
            default -> {
                help(sender);
                return true;
            }
        }
    }

    private void help(CommandSender sender) {
        sender.sendMessage("§6ServerBots — команды:");
        sender.sendMessage("§e/bots add <ник> §7— добавить запись в TAB");
        sender.sendMessage("§e/bots remove <ник> §7— удалить");
        sender.sendMessage("§e/bots rename <старый> <новый> §7— переименовать");
        sender.sendMessage("§e/bots list §7— список");
        sender.sendMessage("§e/bots clear §7— удалить всех");
        sender.sendMessage("§e/bots reload §7— перезагрузить");
        sender.sendMessage("§e/bots status §7— статус");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return Arrays.asList("add", "remove", "rename", "list", "clear", "reload", "status", "help")
                .stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        if (args.length == 2 && Set.of("remove", "rename").contains(args[0].toLowerCase(Locale.ROOT)))
            return bots.keySet().stream().map(this::displayName).filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        return Collections.emptyList();
    }
}
