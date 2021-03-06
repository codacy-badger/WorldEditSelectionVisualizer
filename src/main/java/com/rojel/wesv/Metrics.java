/*
 * Copyright 2011-2013 Tyler Blair. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 *
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ''AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE AUTHOR OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those of the
 * authors and contributors and should not be interpreted as representing official policies,
 * either expressed or implied, of anybody else.
 */

package com.rojel.wesv;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.zip.GZIPOutputStream;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * An external, MCStats + BStats (Minecraft plugins statistics) communication class.
 * This class will set up and send out data about how this plugin is used
 * unless the administrator opts-out of sending plugin statistics.
 *
 * Administrators can opt-out of sending statistical information by setting
 * the "opt-out" option to "false" in config.yml file in plugins/PluginMetrics
 * folder.
 *
 * @author  Hidendra
 * @author  Martin Ambrus
 * @since   1.0a
 */
public class Metrics {

    /**
     * The base url of the metrics domain.
     */
    private static final String BASE_URL = "http://report.mcstats.org";

    /**
     * The url used to report a server's status.
     */
    private static final String REPORT_URL = "/plugin/%s";

    /**
     * Interval of time to ping (in minutes).
     */
    private static final int PING_INTERVAL = 15 * 1200;

    /***
     * The key from config used to determine whether an administrator
     * is opting-out of sending statistical information.
     */
    private static final String optOutKey = "opt-out";

    /***
     * The key from config used to read and write the unique server ID.
     * @see guid
     */
    private static final String guidKey = "guid";

    /***
     * This is used when logging to the console for debugging and informational purposes.
     */
    private static final String metricsName = "Metrics";

    /**
     * The current revision number.
     */
    private static final int REVISION = 7;

    /***
     * Last 4 characters from a hex conversion of string which will be removed.
     */
    private static final int HEX_ERASABLE_EXTRA = 4;

    /**
     * The plugin this metrics submits for.
     */
    private final Plugin plugin;

    /**
     * All of the custom graphs to submit to metrics.
     */
    private final Set<Graph> graphs = Collections.synchronizedSet(new HashSet<Graph>());

    /**
     * The plugin configuration file.
     */
    private final YamlConfiguration configuration;

    /**
     * The plugin configuration file.
     */
    private final File configurationFile;

    /**
     * Unique server id.
     */
    private final String guid;

    /**
     * Debug mode.
     */
    private final boolean debug;

    /**
     * Lock for synchronization.
     */
    private final Object optOutLock = new Object();

    /**
     * The scheduled task.
     */
    private volatile BukkitTask task;

    /***
     * Constructor, creates an instance of Metrics.
     *
     * ```java
     * final Metrics metrics = new Metrics(yourPluginInstance);
     * metrics.start();
     * ```
     *
     * @param plugin The actual plugin Metrics will be sent for.
     * @throws IOException if there is a problem with reading the configuration YAML file.
     */
    public Metrics(final Plugin plugin) throws IOException {
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin cannot be null");
        }

        this.plugin = plugin;

        // load the config
        this.configurationFile = this.getConfigFile();
        this.configuration = YamlConfiguration.loadConfiguration(this.configurationFile);

        // add some defaults
        this.configuration.addDefault(Metrics.optOutKey, false);
        this.configuration.addDefault(Metrics.guidKey, UUID.randomUUID().toString());
        this.configuration.addDefault("debug", false);

        // Do we need to create the file?
        if (this.configuration.get(Metrics.guidKey, null) == null) {
            this.configuration.options().header("http://mcstats.org").copyDefaults(true);
            this.configuration.save(this.configurationFile);
        }

        // Load the guid then
        this.guid = this.configuration.getString(Metrics.guidKey);
        this.debug = this.configuration.getBoolean("debug", false);
    }

    /**
     * Construct and create a Graph that can be used to separate specific plotters to their own graphs on the metrics
     * website. Plotters can be added to the graph object returned.
     *
     * ```java
     * final Metrics.Graph checkForAxeGraph = metrics.createGraph("Check for axe");
     * ```
     *
     * @param  name The name of the graph
     * @return Graph object created. Will never return NULL under normal circumstances unless bad parameters are given
     */
    public Graph createGraph(final String name) {
        if (name == null) {
            throw new IllegalArgumentException("Graph name cannot be null");
        }

        // Construct the graph object
        final Graph graph = new Graph(name);

        // Now we can add our graph
        this.graphs.add(graph);

        // and return back
        return graph;
    }

    /**
     * Add a Graph object to BukkitMetrics that represents data for the plugin that should be sent to the backend.
     *
     * ```java
     * final Metrics metrics = new Metrics(yourPluginInstance);
     * final Metrics.Graph checkForAxeGraph = metrics.createGraph("Check for axe");
     * metrics.addGraph(checkForAxeGraph);
     * ```
     *
     * @param graph The name of the graph.
     */
    public void addGraph(final Graph graph) {
        if (graph == null) {
            throw new IllegalArgumentException("Graph cannot be null");
        }

        this.graphs.add(graph);
    }

    /**
     * Start measuring statistics. This will immediately create an async repeating task as the plugin and send the
     * initial data to the metrics backend, and then after that it will post in increments of PING_INTERVAL * 1200
     * ticks.
     *
     * ```java
     * final Metrics metrics = new Metrics(yourPluginInstance);
     * metrics.start();
     * ```
     *
     * @return True if statistics measuring is running, otherwise false.
     */
    public boolean start() {
        synchronized (this.optOutLock) {
            // Did we opt out?
            if (this.isOptOut()) {
                return false;
            }

            // Is metrics already running?
            if (this.task != null) {
                return true;
            }

            // Begin hitting the server with glorious data
            this.task = this.plugin.getServer().getScheduler().runTaskTimerAsynchronously(this.plugin, new Runnable() {

                private boolean firstPost = true;

                @Override
                public void run() {
                    try {
                        // This has to be synchronized or it can collide with the disable method.
                        synchronized (Metrics.this.optOutLock) {
                            // Disable Task, if it is running and the server owner decided to opt-out
                            if (Metrics.this.isOptOut() && Metrics.this.task != null) {
                                Metrics.this.task.cancel();
                                Metrics.this.task = null;
                                // Tell all plotters to stop gathering information.
                                for (final Graph graph : Metrics.this.graphs) {
                                    graph.onOptOut();
                                }
                            }
                        }

                        // We use the inverse of firstPost because if it is the first time we are posting,
                        // it is not a interval ping, so it evaluates to FALSE
                        // Each time thereafter it will evaluate to TRUE, i.e PING!
                        Metrics.this.postPlugin(!this.firstPost);

                        // After the first post we set firstPost to false
                        // Each post thereafter will be a ping
                        this.firstPost = false;
                    } catch (final IOException e) {
                        if (Metrics.this.debug) {
                            Bukkit.getLogger().log(Level.INFO, metricsName + " " + e.getMessage());
                        }
                    }
                }
            }, 0, PING_INTERVAL);

            return true;
        }
    }

    /**
     * Has the server owner denied plugin metrics?
     *
     * ```java
     * final Metrics metrics = new Metrics(yourPluginInstance);
     * if (!metrics.isOptOut) {
     *   metrics.start();
     * }
     * ```
     *
     * @return true if metrics should be opted out of it
     */
    public boolean isOptOut() {
        synchronized (this.optOutLock) {
            try {
                // Reload the metrics file
                this.configuration.load(this.getConfigFile());
            } catch (final IOException ex) {
                if (this.debug) {
                    Bukkit.getLogger().log(Level.INFO, Metrics.metricsName + " " + ex.getMessage());
                }
                return true;
            } catch (final InvalidConfigurationException ex) {
                if (this.debug) {
                    Bukkit.getLogger().log(Level.INFO, Metrics.metricsName + " " + ex.getMessage());
                }
                return true;
            }
            return this.configuration.getBoolean(Metrics.optOutKey, false);
        }
    }

    /**
     * Enables metrics for the server by setting "opt-out" to false in the config file and starting the metrics task.
     *
     * @throws IOException If there is a problem saving the configuration YAML file.
     */
    public void enable() throws IOException {
        // This has to be synchronized or it can collide with the check in the task.
        synchronized (this.optOutLock) {
            // Check if the server owner has already set opt-out, if not, set it.
            if (this.isOptOut()) {
                this.configuration.set(Metrics.optOutKey, false);
                this.configuration.save(this.configurationFile);
            }

            // Enable Task, if it is not running
            if (this.task == null) {
                this.start();
            }
        }
    }

    /**
     * Disables metrics for the server by setting "opt-out" to true in the config file and canceling the metrics task.
     *
     * @throws IOException If there is a problem saving the configuration YAML file.
     */
    public void disable() throws IOException {
        // This has to be synchronized or it can collide with the check in the task.
        synchronized (this.optOutLock) {
            // Check if the server owner has already set opt-out, if not, set it.
            if (!this.isOptOut()) {
                this.configuration.set(Metrics.optOutKey, true);
                this.configuration.save(this.configurationFile);
            }

            // Disable Task, if it is running
            if (this.task != null) {
                this.task.cancel();
                this.task = null;
            }
        }
    }

    /**
     * Gets the File object of the config file that should be used to store data such as the GUID and opt-out status
     *
     * @return the File object for the config file
     */
    private File getConfigFile() {
        // I believe the easiest way to get the base folder (e.g craftbukkit set via -P) for plugins to use
        // is to abuse the plugin object we already have
        // plugin.getDataFolder() => base/plugins/PluginA/
        // pluginsFolder => base/plugins/
        // The base is not necessarily relative to the startup directory.
        final File pluginsFolder = this.plugin.getDataFolder().getParentFile();

        // return => base/plugins/PluginMetrics/config.yml
        return new File(new File(pluginsFolder, "PluginMetrics"), "config.yml");
    }

    /**
     * Gets the online player (backwards compatibility)
     *
     * @return online player amount
     */
    private int getOnlinePlayers() {
        try {
            final Method onlinePlayerMethod = Server.class.getMethod("getOnlinePlayers");
            if (onlinePlayerMethod.getReturnType().equals(Collection.class)) {
                return ((Collection<?>) onlinePlayerMethod.invoke(Bukkit.getServer())).size();
            } else {
                return ((Player[]) onlinePlayerMethod.invoke(Bukkit.getServer())).length;
            }
        } catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException
                | InvocationTargetException ex) {
            if (this.debug) {
                Bukkit.getLogger().log(Level.INFO, Metrics.metricsName + " " + ex.getMessage());
            }
        }

        return 0;
    }

    /**
     * Appends server software specific section to the JSON string to be sent.
     *
     * @param  json - The JSON string with all statistics to be sent out.
     * @return This method returns the same JSON StringBuilder that was passed to it.
     * @throws IOException If there is a problem with encoding of the data.
     */
    private StringBuilder getServerData(final StringBuilder json) throws IOException {
        final boolean onlineMode = Bukkit.getServer().getOnlineMode(); // TRUE if online mode is enabled
        final String pluginVersion = this.plugin.getDescription().getVersion();
        final String serverVersion = Bukkit.getVersion();
        final int playersOnline = this.getOnlinePlayers();

        // The plugin's description file containg all of the plugin data such as name, version, author, etc
        appendJSONPair(json, "guid", this.guid);
        appendJSONPair(json, "plugin_version", pluginVersion);
        appendJSONPair(json, "server_version", serverVersion);
        appendJSONPair(json, "players_online", Integer.toString(playersOnline));
        appendJSONPair(json, "auth_mode", onlineMode ? "1" : "0");

        // New data as of R6
        final String osname = System.getProperty("os.name");
        String osarch = System.getProperty("os.arch");
        final String osversion = System.getProperty("os.version");
        final String javaVersion = System.getProperty("java.version");
        final int coreCount = Runtime.getRuntime().availableProcessors();

        // normalize os arch .. amd64 -> x86_64
        if ("amd64".equals(osarch)) {
            osarch = "x86_64";
        }

        appendJSONPair(json, "osname", osname);
        appendJSONPair(json, "osarch", osarch);
        appendJSONPair(json, "osversion", osversion);
        appendJSONPair(json, "cores", Integer.toString(coreCount));
        appendJSONPair(json, "java_version", javaVersion);

        return json;
    }

    /**
     * Appends graphs section to the JSON string to be sent.
     *
     * @param  json - The JSON string with all statistics to be sent out.
     * @param  graphs - Data to be used for statistics going into graphs.
     * @return This method returns the same JSON StringBuilder that was passed to it.
     * @throws IOException If there is a problem with encoding of the data.
     */
    private StringBuilder getGraphsData(final StringBuilder json, final Set<Graph> graphs) throws IOException {
        synchronized (graphs) {
            json.append(",\"graphs\":{");

            boolean firstGraph = true;

            final Iterator<Graph> iter = graphs.iterator();
            final StringBuilder graphJson = new StringBuilder();

            while (iter.hasNext()) {
                final Graph graph = iter.next();

                graphJson.setLength(0);
                graphJson.append('{');

                for (final AbstractPlotter plotter : graph.getPlotters()) {
                    appendJSONPair(graphJson, plotter.getColumnName(), Integer.toString(plotter.getValue()));
                }

                graphJson.append('}');

                if (!firstGraph) {
                    json.append(',');
                }

                json.append(escapeJSON(graph.getName()));
                json.append(':');
                json.append(graphJson);

                firstGraph = false;
            }

            json.append('}');
        }

        return json;
    }

    /***
     * Sends the statistics in JSON format.
     *
     * @param  json - The actual statistics to send, in JSON format.
     * @throws MalformedURLException If the URL to send data to is invalid.
     * @throws IOException If there is a problem with opening the connection or writing the output buffer.
     */
    private void sendStats(final StringBuilder json) throws MalformedURLException, IOException {
        final String pluginName = this.plugin.getDescription().getName();

        // Create the url
        final URL url = new URL(BASE_URL + String.format(REPORT_URL, urlEncode(pluginName)));

        // Connect to the website
        URLConnection connection;

        // Mineshafter creates a socks proxy, so we can safely bypass it
        // It does not reroute POST requests so we need to go around it
        if (this.isMineshafterPresent()) {
            connection = url.openConnection(Proxy.NO_PROXY);
        } else {
            connection = url.openConnection();
        }

        final byte[] uncompressed = json.toString().getBytes();
        final byte[] compressed = gzip(json.toString());

        // Headers
        connection.addRequestProperty("User-Agent", "MCStats/" + REVISION);
        connection.addRequestProperty("Content-Type", "application/json");
        connection.addRequestProperty("Content-Encoding", "gzip");
        connection.addRequestProperty("Content-Length", Integer.toString(compressed.length));
        connection.addRequestProperty("Accept", "application/json");
        connection.addRequestProperty("Connection", "close");

        connection.setDoOutput(true);

        if (this.debug) {
            System.out.println(Metrics.metricsName + " Prepared request for " + pluginName + " uncompressed="
                    + uncompressed.length + " compressed=" + compressed.length);
        }

        // Write the data
        final OutputStream os = connection.getOutputStream();
        os.write(compressed);
        os.flush();

        this.readServerResponse(connection, os);
    }

    /***
     * Reads what came back from the server after we sent the data.
     *
     * @param  connection - The connection to the server which was used to send the data.
     * @param  os         - Output stream that was used to write the data out.
     * @throws IOException If there is a problem with closing any of the resources
     *                     or the received data contains an error.
     */
    private void readServerResponse(final URLConnection connection, final OutputStream os) throws IOException {
        // Now read the response
        final BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        String response = reader.readLine();

        // close resources
        os.close();
        reader.close();

        if (response == null || response.startsWith("ERR") || response.charAt(0) == '7') {
            if (response == null) {
                response = "null";
            } else if (response.charAt(0) == '7') {
                response = response.substring(response.startsWith("7,") ? 2 : 1);
            }

            throw new IOException(response);
        } else {
            // Is this the first update this hour?
            if ("1".equals(response) || response.contains("This is your first update this hour")) {
                synchronized (this.graphs) {
                    final Iterator<Graph> iter = this.graphs.iterator();

                    while (iter.hasNext()) {
                        final Graph graph = iter.next();

                        for (final AbstractPlotter plotter : graph.getPlotters()) {
                            plotter.reset();
                        }
                    }
                }
            }
        }
    }

    /**
     * Generic method that posts a plugin to the metrics website.
     *
     * @param isPing True if this post is a PING request.
     * @throws IOException If there is a problem with encoding of the data.
     */
    private void postPlugin(final boolean isPing) throws IOException {
        // Construct the post data
        final StringBuilder json = new StringBuilder(1024);
        json.append('{');

        // add server software specific section
        this.getServerData(json);

        // If we're pinging, append it
        if (isPing) {
            appendJSONPair(json, "ping", "1");
        }

        if (this.graphs.size() > 0) {
            this.getGraphsData(json, this.graphs);
        }

        // close json
        json.append('}');

        // send the data
        this.sendStats(json);
    }

    /**
     * GZip compress a string of bytes
     *
     * @param  input String input to apply GZIP compression to.
     * @return Returns a byte array for the compressed string.
     */
    public static byte[] gzip(final String input) {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream gzos = null;

        try {
            gzos = new GZIPOutputStream(baos);
            gzos.write(input.getBytes("UTF-8"));
        } catch (final IOException e) {
            Bukkit.getLogger().log(Level.SEVERE, e.getMessage(), e);
        } finally {
            if (gzos != null) {
                try {
                    gzos.close();
                } catch (final IOException e) {
                    Bukkit.getLogger().log(Level.INFO,
                            "[Metrics] Could not close a GZip Stream. This error is not fatal only informational.", e);
                }
            }
        }

        return baos.toByteArray();
    }

    /**
     * Check if mineshafter is present. If it is, we need to bypass it to send POST requests
     *
     * @return true if mineshafter is installed on the server
     */
    private boolean isMineshafterPresent() {
        try {
            Class.forName("mineshafter.MineServer");
            return true;
        } catch (final ClassNotFoundException ex) {
            return false;
        }
    }

    /**
     * Appends a json encoded key/value pair to the given string builder.
     *
     * @param json The JSON string to be sent out to the server.
     * @param key Key to append to the JSON string.
     * @param value Value for the key to be appended to the JSON string.
     * @throws UnsupportedEncodingException If the encoding of the data is invalid.
     */
    private static void appendJSONPair(final StringBuilder json, final String key, final String value)
            throws UnsupportedEncodingException {
        boolean isValueNumeric = false;

        try {
            if ("0".equals(value) || !value.endsWith("0")) {
                Double.parseDouble(value);
                isValueNumeric = true;
            }
        } catch (final NumberFormatException e) {
            isValueNumeric = false;
        }

        if (json.charAt(json.length() - 1) != '{') {
            json.append(',');
        }

        json.append(escapeJSON(key));
        json.append(':');

        if (isValueNumeric) {
            json.append(value);
        } else {
            json.append(escapeJSON(value));
        }
    }

    /**
     * Escape a string to create a valid JSON string
     *
     * @param text The actual JSON string to escape.
     * @return Returns the original JSON string escaped for validation purposes.
     */
    private static String escapeJSON(final String text) {
        final StringBuilder builder = new StringBuilder();

        builder.append('"');
        for (int index = 0; index < text.length(); index++) {
            final char chr = text.charAt(index);

            switch (chr) {
                case '"':
                case '\\':
                    builder.append('\\');
                    builder.append(chr);
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                default:
                    if (chr < ' ') {
                        final String t = "000" + Integer.toHexString(chr);
                        builder.append("\\u" + t.substring(t.length() - HEX_ERASABLE_EXTRA));
                    } else {
                        builder.append(chr);
                    }
                    break;
            }
        }
        builder.append('"');

        return builder.toString();
    }

    /**
     * Encode text as UTF-8
     *
     * @param text the text to encode
     * @return the encoded text, as UTF-8
     * @throws UnsupportedEncodingException If the encoding of the data is invalid.
     */
    private static String urlEncode(final String text) throws UnsupportedEncodingException {
        return URLEncoder.encode(text, "UTF-8");
    }

    /**
     * Represents a custom graph on the website
     */
    public static final class Graph {

        /**
         * The graph's name, alphanumeric and spaces only :) If it does not comply to the above when submitted, it is
         * rejected
         */
        private final String name;

        /**
         * The set of plotters that are contained within this graph
         */
        private final Set<AbstractPlotter> plotters = new LinkedHashSet<AbstractPlotter>();

        /***
         * Constructor. Will take this graph's name and save it.
         *
         * @param name Name of the new graph.
         */
        private Graph(final String name) {
            this.name = name;
        }

        /**
         * Gets the graph's name
         *
         * @return the Graph's name
         */
        public String getName() {
            return this.name;
        }

        /**
         * Add a plotter to the graph, which will be used to plot entries
         *
         * @param plotter the plotter to add to the graph
         */
        public void addPlotter(final AbstractPlotter plotter) {
            this.plotters.add(plotter);
        }

        /**
         * Remove a plotter from the graph
         *
         * @param plotter the plotter to remove from the graph
         */
        public void removePlotter(final AbstractPlotter plotter) {
            this.plotters.remove(plotter);
        }

        /**
         * Gets an <b>unmodifiable</b> set of the plotter objects in the graph
         *
         * @return an unmodifiable {@link java.util.Set} of the plotter objects
         */
        public Set<AbstractPlotter> getPlotters() {
            return Collections.unmodifiableSet(this.plotters);
        }

        @Override
        public int hashCode() {
            return this.name.hashCode();
        }

        @Override
        public boolean equals(final Object object) {
            if (!(object instanceof Graph)) {
                return false;
            }

            final Graph graph = (Graph) object;
            return graph.name.equals(this.name);
        }

        /**
         * Called when the server owner decides to opt-out of BukkitMetrics while the server is running.
         */
        private void onOptOut() {
            // extendable
        }
    }

    /**
     * Interface used to collect custom data for a plugin
     */
    public abstract static class AbstractPlotter {

        /**
         * The plot's name
         */
        private final String name;

        /**
         * Construct a plotter with the default plot name
         */
        public AbstractPlotter() {
            this("Default");
        }

        /**
         * Construct a plotter with a specific plot name
         *
         * @param name the name of the plotter to use, which will show up on the website
         */
        public AbstractPlotter(final String name) {
            this.name = name;
        }

        /**
         * Get the current value for the plotted point. Since this function defers to an external function it may or may
         * not return immediately thus cannot be guaranteed to be thread friendly or safe. This function can be called
         * from any thread so care should be taken when accessing resources that need to be synchronized.
         *
         * @return the current value for the point to be plotted.
         */
        public abstract int getValue();

        /**
         * Get the column name for the plotted point
         *
         * @return the plotted point's column name
         */
        public String getColumnName() {
            return this.name;
        }

        /**
         * Called after the website graphs have been updated
         */
        public void reset() {
            // extendable
        }

        @Override
        public int hashCode() {
            return this.getColumnName().hashCode();
        }

        @Override
        public boolean equals(final Object object) {
            if (!(object instanceof AbstractPlotter)) {
                return false;
            }

            final AbstractPlotter plotter = (AbstractPlotter) object;
            return plotter.name.equals(this.name) && plotter.getValue() == this.getValue();
		}
	}
}