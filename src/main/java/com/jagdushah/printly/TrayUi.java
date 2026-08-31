package com.jagdushah.printly;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Status indicator in the system tray: which printers are up, reconnect, open config/log, quit.
 *
 * <p>Entirely optional — a headless machine or a Linux desktop without a tray just runs without
 * it, which is also what {@code --no-tray} forces.
 */
public final class TrayUi {

    private final Config config;
    private final PrintRouter router;
    private final Runnable onQuit;
    private final boolean panel;

    private TrayIcon icon;
    private MenuItem headerItem;
    private final Map<String, MenuItem> printerItems = new LinkedHashMap<>();
    private javax.swing.Timer refresh;
    private Boolean lastAllOnline;

    public TrayUi(Config config, PrintRouter router, boolean panel, Runnable onQuit) {
        this.config = config;
        this.router = router;
        this.panel = panel;
        this.onQuit = onQuit;
    }

    /** @return true when a tray icon was actually installed */
    public boolean install() {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            Log.info("no system tray available — running without a tray icon");
            return false;
        }
        try {
            PopupMenu menu = new PopupMenu();

            headerItem = new MenuItem("Printly " + Main.VERSION);
            headerItem.setEnabled(false);
            menu.add(headerItem);

            MenuItem endpoint = new MenuItem("http://" + config.bindAddress + ":" + config.port);
            endpoint.setEnabled(false);
            menu.add(endpoint);
            menu.addSeparator();

            for (PrinterConnection c : router.labelPrinters()) {
                MenuItem item = new MenuItem(c.target().name() + " — checking…");
                item.setEnabled(false);
                printerItems.put(c.target().name(), item);
                menu.add(item);
            }
            if (printerItems.isEmpty()) {
                MenuItem none = new MenuItem("No printers configured");
                none.setEnabled(false);
                menu.add(none);
            }
            menu.addSeparator();

            if (panel) {
                // First item under the printer list, because it is the one an operator wants: the
                // tray is where they notice something is wrong, and the panel is where they fix it.
                MenuItem openPanel = new MenuItem("Open Control Panel");
                openPanel.setFont(openPanel.getFont() == null ? null : openPanel.getFont().deriveFont(java.awt.Font.BOLD));
                openPanel.addActionListener(e -> browse("http://" + config.bindAddress + ":" + config.port + "/"));
                menu.add(openPanel);
                menu.addSeparator();
            }

            MenuItem reconnect = new MenuItem("Reconnect all printers");
            reconnect.addActionListener(e -> router.reconnectAll());
            menu.add(reconnect);

            MenuItem openConfig = new MenuItem("Open config file");
            openConfig.addActionListener(e -> open(config.file));
            menu.add(openConfig);

            MenuItem openLog = new MenuItem("Open log file");
            openLog.addActionListener(e -> open(Log.file()));
            menu.add(openLog);
            menu.addSeparator();

            MenuItem quit = new MenuItem("Quit Printly");
            quit.addActionListener(e -> onQuit.run());
            menu.add(quit);

            icon = new TrayIcon(renderIcon(iconSize(), true), tooltip(), menu);
            icon.setImageAutoSize(true);
            SystemTray.getSystemTray().add(icon);

            refresh = new javax.swing.Timer(3000, e -> update());
            refresh.start();
            update();
            return true;
        } catch (AWTException | RuntimeException e) {
            Log.warn("could not install the tray icon: " + e);
            return false;
        }
    }

    private void update() {
        boolean allOnline = true;
        for (PrinterConnection c : router.labelPrinters()) {
            boolean online = c.online();
            allOnline &= online;
            MenuItem item = printerItems.get(c.target().name());
            if (item != null) {
                item.setLabel(c.target().name() + " — " + (online ? "online" : "OFFLINE") + "  (" + c.target().address() + ")");
            }
        }
        if (icon != null) {
            icon.setToolTip(tooltip());
            if (lastAllOnline == null || lastAllOnline != allOnline) {
                icon.setImage(renderIcon(iconSize(), allOnline));
                lastAllOnline = allOnline;
            }
        }
    }

    private String tooltip() {
        int total = router.labelPrinters().size();
        long up = router.labelPrinters().stream().filter(PrinterConnection::online).count();
        return "Printly — " + up + "/" + total + " printers online\n"
                + "http://" + config.bindAddress + ":" + config.port;
    }

    /**
     * Open the Control Panel in the default browser.
     *
     * <p>{@code Desktop.browse} first; on a Linux desktop without the AWT Desktop integration it
     * falls back to {@code xdg-open}. A failure is logged rather than shown: the tooltip already
     * carries the address, so the worst case is someone typing it.
     */
    private static void browse(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(java.net.URI.create(url));
                return;
            }
        } catch (IOException | RuntimeException e) {
            Log.warn("could not open " + url + " with the desktop browser: " + e);
        }
        try {
            new ProcessBuilder("xdg-open", url).start();
        } catch (IOException | RuntimeException e) {
            Log.warn("could not open " + url + ": " + e + " — open it by hand");
        }
    }

    private static Dimension iconSize() {
        try {
            Dimension d = SystemTray.getSystemTray().getTrayIconSize();
            if (d != null && d.width > 0 && d.height > 0) {
                return d;
            }
        } catch (RuntimeException ignored) {
            // fall through to a sane default
        }
        return new Dimension(16, 16);
    }

    /** Drawn rather than shipped as an asset, so the build stays a plain javac invocation. */
    private static BufferedImage renderIcon(Dimension size, boolean healthy) {
        int w = Math.max(16, size.width);
        int h = Math.max(16, size.height);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int pad = Math.max(1, w / 8);
        g.setColor(new Color(0x33, 0x37, 0x3D));
        g.fillRoundRect(pad, h / 3, w - 2 * pad, h - h / 3 - pad, pad, pad);

        g.setColor(Color.WHITE);
        g.fillRect(pad + pad / 2, pad, w - 3 * pad, h / 3);
        g.fillRect(pad + pad / 2, h - h / 3 - pad, w - 3 * pad, h / 4);

        int dot = Math.max(4, w / 3);
        g.setColor(healthy ? new Color(0x2E, 0xA0, 0x43) : new Color(0xD1, 0x24, 0x2F));
        g.fillOval(w - dot - 1, h - dot - 1, dot, dot);

        g.dispose();
        return img;
    }

    private void open(Path path) {
        try {
            if (path == null || !Files.exists(path)) {
                notice("Not available", "No file at " + path);
                return;
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(path.toFile());
            } else {
                notice("Path", path.toString());
            }
        } catch (IOException | RuntimeException e) {
            notice("Could not open", String.valueOf(e));
        }
    }

    private void notice(String caption, String message) {
        if (icon != null) {
            icon.displayMessage(caption, message, TrayIcon.MessageType.INFO);
        } else {
            Log.info(caption + ": " + message);
        }
    }

    public void remove() {
        if (refresh != null) {
            refresh.stop();
        }
        if (icon != null) {
            SystemTray.getSystemTray().remove(icon);
            icon = null;
        }
    }
}
