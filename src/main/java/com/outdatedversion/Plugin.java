package com.outdatedversion;

import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.Configuration;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1NodeList;
import io.kubernetes.client.models.V1PodList;
import io.kubernetes.client.util.Config;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author Ben Watkins
 * @since Nov/08/2019
 */
public class Plugin extends JavaPlugin implements Listener {
    private static final int POLLING_INTERVAL_SECONDS = 2;
    private static final String NAMESPACE = "default";

    private ApiClient client;
    private CoreV1Api api;

    private Location location;

    private List<String> activePods = new ArrayList<>();
    private List<String> activeNodes = new ArrayList<>();

    @Override
    public void onEnable() {
        this.location = new Location(Bukkit.getWorld("world"), 98, 74, 60);

        try {
            this.client = Config.defaultClient();
            this.client.getHttpClient().setReadTimeout(0, TimeUnit.SECONDS); // infinite timeout
            Configuration.setDefaultApiClient(this.client);
            this.api = new CoreV1Api();
        } catch (Exception ex) {
            this.getSLF4JLogger().error("Failed to create client", ex);
        }

        this.getServer().getPluginManager().registerEvents(this, this);
        this.getServer().getScheduler().runTaskTimer(this, () -> {
            this.getSLF4JLogger().info("entity task");

            try {
                this.spawnEntities();
            } catch (ApiException ex) {
                this.getSLF4JLogger().error("Failed to spawn k8s entities", ex);
            }
        }, 0, 20 * Plugin.POLLING_INTERVAL_SECONDS);
    }

    @Override
    public void onDisable() {
        Objects.requireNonNull(Bukkit.getWorld("world")).getEntities().stream().filter(entity -> {
            final List<MetadataValue> meta = entity.getMetadata("k8s");

            return meta.size() > 1;
        }).forEach(Entity::remove);
    }

    @EventHandler
    public void command(PlayerCommandPreprocessEvent event) {
        final Player player = event.getPlayer();

        if (event.getMessage().equalsIgnoreCase("/phantom")) {
            event.setCancelled(true);
            final Phantom phantom = player.getWorld().spawn(player.getLocation(), Phantom.class);

            phantom.setTarget(player);
        }
        else if (event.getMessage().equalsIgnoreCase("/spawn")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("Fetching pods..");

            try {
                this.spawnEntities();
            } catch (ApiException ex) {
                event.getPlayer().sendMessage(ChatColor.RED + "Failed to spawn entities");
                ex.printStackTrace();
            }
        }
        else if (event.getMessage().equalsIgnoreCase("/setpodspawn")) {
            event.setCancelled(true);

            this.getSLF4JLogger().info("Changing pod spawn location. old: {}, new: {}", this.location, player.getLocation());
            this.location = player.getLocation();

            // Removing old ones
            for (final Entity entity : this.location.getWorld().getEntities()) {
                final List<MetadataValue> meta = entity.getMetadata("k8s");

                if (meta.size() > 0) {
                    entity.remove();
                }
            }
            this.activePods.clear();
            this.activeNodes.clear();
            player.sendMessage("Removed entities from previous spot");

            try {
                this.spawnEntities();
            } catch (ApiException ex) {
                event.getPlayer().sendMessage(ChatColor.RED + "Failed to spawn entities");
                ex.printStackTrace();
            }
            player.sendMessage("Populated entities in new spot");

            player.sendMessage(ChatColor.GREEN + "Updated location");
        }
    }

    @EventHandler
    public void death(EntityDeathEvent event) {
        List<MetadataValue> meta = event.getEntity().getMetadata("k8s-pod-name");

        if (meta.size() < 1) {
            return;
        }

        final String podName = meta.get(0).asString();

        this.getSLF4JLogger().info("removing pod {} (namespace {})", podName, Plugin.NAMESPACE);

        this.activePods.remove(podName);

        try {
            this.api.deleteNamespacedPod(podName, Plugin.NAMESPACE,null, null, null, null, null, null);
        } catch (Exception ex) {
//            if (ex.getCause().getMessage().startsWith("Expected a string but")) {
//                return;
//            }

//            ex.printStackTrace();
        }
    }

    private void spawnEntities() throws ApiException {
        final List<String> pods = this.getRunningPods();
        final List<String> nodes = this.getNodes();

        for (final String nodeName : nodes) {
            System.out.println("Found node: " + nodeName + ", already have: " + this.activeNodes.contains(nodeName));

            if (this.activeNodes.contains(nodeName)) {
                continue;
            }

            this.activeNodes.add(nodeName);

            final Entity entity = this.location.getWorld().spawnEntity(this.location, EntityType.PHANTOM);

            entity.setCustomName(ChatColor.AQUA + nodeName);
            entity.setCustomNameVisible(true);
            entity.setMetadata("k8s", new FixedMetadataValue(this, true));
            entity.setMetadata("k8s-node-name", new FixedMetadataValue(this, nodeName));
        }

        for (final String podName : pods) {
            System.out.println("Found pod: " + podName + ", already have: " + this.activePods.contains(podName));

            if (this.activePods.contains(podName)) {
                continue;
            }

            this.activePods.add(podName);

            final EntityType[] options = new EntityType[] {
                EntityType.ZOMBIE,
                EntityType.SKELETON,
                EntityType.SPIDER,
                EntityType.PIG_ZOMBIE
            };
            final EntityType type = options[new Random().nextInt(options.length)];

            final Entity entity = this.location.getWorld().spawnEntity(this.location, type);

            entity.setCustomName(ChatColor.YELLOW + podName);
            entity.setCustomNameVisible(true);
            entity.setMetadata("k8s", new FixedMetadataValue(this, true));
            entity.setMetadata("k8s-pod-name", new FixedMetadataValue(this, podName));
        }

        if (this.activePods.size() > pods.size()) {
            this.getSLF4JLogger().info("Possible zombie pod entities; {} > {}", this.activePods.size(), pods.size());

            // TODO: Performance
            for (final String pod : activePods) {
                if (!pods.contains(pod)) {
                    for (final Entity entity : this.location.getWorld().getEntities()) {
                        final List<MetadataValue> meta = entity.getMetadata("k8s-pod-name");

                        if (meta.size() > 0) {
                            if (meta.get(0).asString().equalsIgnoreCase(pod)) {
                                this.getSLF4JLogger().info("Removing zombie pod entity: {}", pod);
                                entity.remove();
                            }
                        }
                    }
                }
            }
        }
    }

    private List<String> getRunningPods() throws ApiException {
        V1PodList list = this.api.listNamespacedPod(Plugin.NAMESPACE, null, null, null, null, null, null, null, null, null);

        return list.getItems()
                    .stream()
                    .filter(pod -> pod.getStatus().getPhase().equals("Running"))
                    .map(pod -> pod.getMetadata().getName()).collect(Collectors.toList());
    }

    private List<String> getNodes() throws ApiException {
        V1NodeList list = this.api.listNode(false, null, null, null, null, null, null, null, null);

        return list.getItems()
                    .stream()
                    .filter(node -> {
                        System.out.println(node.getStatus().getPhase());

                        return true;
                    })
                    .map(node -> node.getMetadata().getName()).collect(Collectors.toList());
    }
}
