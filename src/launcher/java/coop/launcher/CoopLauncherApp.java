package coop.launcher;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.apache.log4j.Logger;

import coop.config.CoopOptionsRegistry;
import coop.net.CoopConnectionDoctor;
import coop.net.CoopPortMapper;

/**
 * Phase 31: the co-op launcher window.
 *
 * <p>It invents no configuration of its own. Every field is a key that already resolves through
 * {@code CoopOptionsStore}, and pressing Launch writes them to
 * {@code saves/common/coop_options.json.data} and starts {@code starsector.exe}. The two things the
 * mod cannot reach - the {@code coop-forks.jar} classpath entry in {@code vmparams} and the mod tick
 * in {@code enabled_mods.json} - are reported with the exact fix and never edited.
 *
 * <p>Engine-free by construction: this source set is compiled without {@code starfarer.api.jar} on
 * the classpath. The mod classes it does reuse ({@link CoopPortMapper},
 * {@link CoopConnectionDoctor}, {@link CoopOptionsRegistry}) do not link to the game API.
 */
public final class CoopLauncherApp {

    private static final Logger LOG = Logger.getLogger(CoopLauncherApp.class);

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Shown in every "shipped default" combo slot; selecting it removes the key from the file. */
    private static final String DEFAULT_ENTRY = "(shipped default)";

    /**
     * Star ages, spelled out because {@code StarAge} lives in {@code starfarer.api.jar} and the
     * launcher is compiled without it. The mod validates the value again
     * ({@code CoopNewGameChoices.parseStarAge}) and warns rather than crashing if this list ever
     * drifts from the engine's.
     */
    private static final List<String> STAR_AGES = List.of("young", "average", "old", "mixed");

    private static final List<String> SECTOR_SIZES = List.of("small", "normal");

    private static final int DEFAULT_PORT = 7777;
    private static final long CHECK_TIMEOUT_MILLIS = 20_000L;
    private static final int CHECK_TICK_MILLIS = 50;

    private final ExecutorService background =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "coop-launcher-worker");
                thread.setDaemon(true);
                return thread;
            });

    private CoopInstallLayout layout;
    private CoopLauncherConfig config;
    private String launcherVersion = "dev";

    private JFrame frame;

    private JRadioButton hostRole;
    private JRadioButton guestRole;

    private JTextField hostPortField;
    private JPasswordField hostPasswordField;
    private JTextField hostSeedField;
    private JTextField publicAddressField;
    private JButton copyInviteButton;
    private JButton checkConnectionButton;

    private JTextField guestHostField;
    private JTextField guestPortField;
    private JPasswordField guestPasswordField;
    private JTextField guestSeedField;
    private JButton testConnectionButton;

    private JPanel hostPanel;
    private JPanel guestPanel;

    private JButton advancedToggle;
    private JPanel advancedPanel;
    private JComboBox<String> portMappingBox;
    private JSpinner reconnectGraceSpinner;
    private JCheckBox reconnectGraceDefault;
    private JComboBox<String> hudCornerBox;
    private JComboBox<String> sectorSizeBox;
    private JComboBox<String> sectorAgeBox;

    private JPanel rowsPanel;
    private JTextArea statusArea;
    private JButton launchButton;

    private JCheckBox includeSaveBox;
    private JButton bugReportButton;

    private List<CoopInstallCheck.Row> installRows = List.of();
    /** The update-check row, kept apart because it arrives on its own schedule and off the disk. */
    private CoopInstallCheck.Row updateRow;
    private String updateUrl = "";
    /**
     * Set once the player empties the host password field themselves. From then on nothing refills
     * it: a host who deliberately wants an open session should not have to fight the launcher about
     * it every time they touch a radio button.
     */
    private boolean hostPasswordCleared;
    /** True while the code, not the player, is writing the host password field. */
    private boolean writingHostPassword;

    private CoopLauncherProbe.HostListener listener;
    private CoopLogTail logTail;
    private Process gameProcess;
    private javax.swing.Timer checkTimer;

    public static void main(String[] args) {
        CoopInstallLayout discovered = CoopInstallLayout.discover();
        File logFile = discovered == null
                ? new File("coop-launcher.log")
                : discovered.launcherLog();
        CoopLauncherLogging.configure(logFile);
        LOG.info("Coop launcher starting; layout " + (discovered == null ? "not found" : discovered));
        SwingUtilities.invokeLater(() -> new CoopLauncherApp().start(discovered));
    }

    private void start(CoopInstallLayout discovered) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ex) {
            LOG.warn("Could not set the system look and feel; using the default", ex);
        }
        this.layout = discovered;
        this.launcherVersion = CoopInstallCheck.launcherVersion();
        buildFrame();
        if (layout == null) {
            append("This folder does not look like a Starsector install, so the launcher could not"
                    + " work out where the game is. Use \"Choose install folder\" to point at it.");
            chooseInstallFolder();
        } else {
            adoptLayout(layout);
        }
        startUpdateCheck();
        frame.setVisible(true);
    }

    // ---- window ---------------------------------------------------------------------------------

    private void buildFrame() {
        frame = new JFrame("Starsector Coop Launcher " + launcherVersion);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                shutdown();
            }
        });

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(buildRolePanel());
        top.add(Box.createVerticalStrut(6));
        hostPanel = buildHostPanel();
        guestPanel = buildGuestPanel();
        top.add(hostPanel);
        top.add(guestPanel);
        top.add(Box.createVerticalStrut(6));
        top.add(buildAdvancedSection());
        top.add(Box.createVerticalStrut(6));
        top.add(buildInstallSection());

        root.add(top, BorderLayout.NORTH);
        root.add(buildStatusSection(), BorderLayout.CENTER);
        root.add(buildBottomBar(), BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setMinimumSize(new Dimension(720, 700));
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    private JPanel buildRolePanel() {
        JPanel panel = titled("Your role");
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        hostRole = new JRadioButton("Host");
        guestRole = new JRadioButton("Guest");
        ButtonGroup group = new ButtonGroup();
        group.add(hostRole);
        group.add(guestRole);
        hostRole.addActionListener(event -> onRoleChanged());
        guestRole.addActionListener(event -> onRoleChanged());
        panel.add(hostRole);
        panel.add(Box.createHorizontalStrut(12));
        panel.add(guestRole);
        panel.add(Box.createHorizontalStrut(16));
        panel.add(new JLabel("The host runs the world. The guest joins it."));
        panel.add(Box.createHorizontalGlue());
        return panel;
    }

    private JPanel buildHostPanel() {
        JPanel panel = titled("Host");
        GridBagLayout grid = new GridBagLayout();
        panel.setLayout(grid);
        int row = 0;

        hostPortField = new JTextField(String.valueOf(DEFAULT_PORT), 8);
        addRow(panel, row++, "Port", hostPortField, null,
                "The TCP and UDP port your partner connects to.");

        hostPasswordField = new JPasswordField(18);
        hostPasswordField.getDocument().addDocumentListener(new PasswordWatcher());
        addRow(panel, row++, "Password", hostPasswordField, null,
                "Optional. Generated for you; both of you have to type the same one."
                        + " The invite carries it.");

        hostSeedField = new JTextField(24);
        JButton generate = new JButton("Generate");
        generate.addActionListener(event -> {
            String seed = CoopSeeds.generate();
            hostSeedField.setText(seed);
            LOG.info("Generated a new seed: " + seed);
            append("New seed " + seed + ". Copy the invite so your partner gets the same one.");
        });
        addRow(panel, row++, "Seed", hostSeedField, generate,
                "Both games have to generate the same sector.");

        publicAddressField = new JTextField(24);
        JButton lookUp = new JButton("Look up");
        lookUp.addActionListener(event -> lookUpPublicAddress(null));
        addRow(panel, row++, "Your public address", publicAddressField, lookUp,
                "Overwrite this with a LAN or VPN address if that is how you connect.");

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        copyInviteButton = new JButton("Copy invite");
        copyInviteButton.addActionListener(event -> copyInvite());
        checkConnectionButton = new JButton("Check my connection");
        checkConnectionButton.addActionListener(event -> checkMyConnection());
        buttons.add(copyInviteButton);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(checkConnectionButton);
        buttons.add(Box.createHorizontalGlue());

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 4;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(6, 4, 2, 4);
        panel.add(buttons, c);
        return panel;
    }

    private JPanel buildGuestPanel() {
        JPanel panel = titled("Guest");
        panel.setLayout(new GridBagLayout());
        int row = 0;

        JButton paste = new JButton("Paste invite");
        paste.addActionListener(event -> pasteInvite());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 4;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 4, 6, 4);
        JPanel pasteRow = new JPanel();
        pasteRow.setLayout(new BoxLayout(pasteRow, BoxLayout.X_AXIS));
        pasteRow.add(paste);
        pasteRow.add(Box.createHorizontalStrut(10));
        pasteRow.add(new JLabel("Fills every field below from the one line your host sent you."));
        pasteRow.add(Box.createHorizontalGlue());
        panel.add(pasteRow, c);

        guestHostField = new JTextField(24);
        addRow(panel, row++, "Host address", guestHostField, null,
                "A name, an IPv4 address, or an IPv6 address.");

        guestPortField = new JTextField(String.valueOf(DEFAULT_PORT), 8);
        addRow(panel, row++, "Port", guestPortField, null, "");

        guestPasswordField = new JPasswordField(18);
        addRow(panel, row++, "Password", guestPasswordField, null,
                "Has to match the host's exactly.");

        guestSeedField = new JTextField(24);
        guestSeedField.setEditable(false);
        addRow(panel, row++, "Seed", guestSeedField, null,
                "From the invite. Only used when you start a new campaign.");

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        testConnectionButton = new JButton("Test connection");
        testConnectionButton.addActionListener(event -> testConnection());
        buttons.add(testConnectionButton);
        buttons.add(Box.createHorizontalStrut(10));
        buttons.add(new JLabel("Ask your host to open their launcher first."));
        buttons.add(Box.createHorizontalGlue());

        c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 4;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(6, 4, 2, 4);
        panel.add(buttons, c);
        return panel;
    }

    private JPanel buildAdvancedSection() {
        JPanel wrapper = new JPanel(new BorderLayout());
        advancedToggle = new JButton("Advanced settings");
        advancedToggle.addActionListener(event -> {
            boolean show = !advancedPanel.isVisible();
            advancedPanel.setVisible(show);
            LOG.info("Advanced settings " + (show ? "shown" : "hidden"));
            frame.pack();
        });
        JPanel togglePanel = new JPanel();
        togglePanel.setLayout(new BoxLayout(togglePanel, BoxLayout.X_AXIS));
        togglePanel.add(advancedToggle);
        togglePanel.add(Box.createHorizontalStrut(10));
        togglePanel.add(new JLabel("Everything here has a working default. Leave it alone unless you"
                + " have a reason."));
        togglePanel.add(Box.createHorizontalGlue());

        advancedPanel = titled("Advanced settings");
        advancedPanel.setLayout(new GridBagLayout());
        int row = 0;

        portMappingBox = combo(CoopOptionsRegistry.require(CoopOptionsRegistry.PORT_MAPPING)
                .allowedValues());
        addRow(advancedPanel, row++, "Port mapping", portMappingBox, null,
                "auto asks your router to forward the port over UPnP. Host only.");

        reconnectGraceSpinner = new JSpinner(new SpinnerNumberModel(60, 0,
                CoopOptionsRegistry.require(CoopOptionsRegistry.RECONNECT_GRACE_SECONDS).max(), 5));
        reconnectGraceDefault = new JCheckBox("use the shipped default", true);
        reconnectGraceDefault.addActionListener(event ->
                reconnectGraceSpinner.setEnabled(!reconnectGraceDefault.isSelected()));
        reconnectGraceSpinner.setEnabled(false);
        addRow(advancedPanel, row++, "Reconnect grace (seconds)", reconnectGraceSpinner,
                reconnectGraceDefault, "How long a dropped link keeps the session alive.");

        hudCornerBox = combo(CoopOptionsRegistry.require(CoopOptionsRegistry.HUD_CORNER)
                .allowedValues());
        addRow(advancedPanel, row++, "Link HUD corner", hudCornerBox, null,
                "Where the one-line link status sits on screen. Local only.");

        sectorSizeBox = combo(SECTOR_SIZES);
        addRow(advancedPanel, row++, "Sector size", sectorSizeBox, null,
                "New campaigns only. Both of you need the same value.");

        sectorAgeBox = combo(STAR_AGES);
        addRow(advancedPanel, row++, "Star age", sectorAgeBox, null,
                "New campaigns only. Both of you need the same value.");

        advancedPanel.setVisible(false);

        wrapper.add(togglePanel, BorderLayout.NORTH);
        wrapper.add(advancedPanel, BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel buildInstallSection() {
        JPanel panel = titled("Install check");
        panel.setLayout(new BorderLayout(4, 4));

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(event -> {
            LOG.info("Install check refreshed by the player");
            refreshInstallRows();
        });
        JButton openInstallDoc = new JButton("Open INSTALL.md");
        openInstallDoc.addActionListener(event -> openInstallDoc());
        JButton chooseFolder = new JButton("Choose install folder");
        chooseFolder.addActionListener(event -> chooseInstallFolder());
        buttons.add(refresh);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(openInstallDoc);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(chooseFolder);
        buttons.add(Box.createHorizontalGlue());

        rowsPanel = new JPanel();
        rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));

        panel.add(buttons, BorderLayout.NORTH);
        panel.add(rowsPanel, BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildStatusSection() {
        JPanel panel = titled("Status");
        panel.setLayout(new BorderLayout(4, 4));
        statusArea = new JTextArea(14, 80);
        statusArea.setEditable(false);
        statusArea.setLineWrap(false);
        statusArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(statusArea);
        scroll.setPreferredSize(new Dimension(700, 240));

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        JButton openLogFolder = new JButton("Open log folder");
        openLogFolder.addActionListener(event -> {
            if (layout == null) {
                append("There is no install to open a log folder for yet.");
                return;
            }
            LOG.info("Opening the log folder " + layout.starsectorCore());
            openPath(layout.starsectorCore());
        });
        bugReportButton = new JButton("Save a bug report");
        bugReportButton.addActionListener(event -> saveBugReport());
        includeSaveBox = new JCheckBox("Include my newest save", true);
        JButton clear = new JButton("Clear");
        clear.addActionListener(event -> statusArea.setText(""));
        buttons.add(openLogFolder);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(bugReportButton);
        buttons.add(Box.createHorizontalStrut(4));
        buttons.add(includeSaveBox);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(clear);
        buttons.add(Box.createHorizontalGlue());

        panel.add(scroll, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildBottomBar() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        launchButton = new JButton("Launch");
        launchButton.addActionListener(event -> launch());
        panel.add(Box.createHorizontalGlue());
        panel.add(new JLabel("Closing this window does not close the game."));
        panel.add(Box.createHorizontalStrut(12));
        panel.add(launchButton);
        return panel;
    }

    // ---- state ----------------------------------------------------------------------------------

    private void adoptLayout(CoopInstallLayout newLayout) {
        this.layout = newLayout;
        CoopLauncherLogging.configure(newLayout.launcherLog());
        LOG.info("Using install " + newLayout);
        append("Install: " + newLayout.installRoot());
        this.config = CoopLauncherConfig.read(newLayout.coopOptions());
        if (config.readError() != null) {
            LOG.warn("Settings file unreadable: " + config.readError());
        } else {
            LOG.info("Settings file " + (config.fileExisted() ? "read, keys " + config.keys()
                    : "not present yet"));
        }
        prefill();
        refreshInstallRows();
    }

    private void prefill() {
        String hostPort = config.value(CoopLauncherConfig.HOST_PORT).trim();
        String connectHost = config.value(CoopLauncherConfig.CONNECT_HOST).trim();
        String connectPort = config.value(CoopLauncherConfig.CONNECT_PORT).trim();

        boolean host = !hostPort.isEmpty() || connectHost.isEmpty();
        hostRole.setSelected(host);
        guestRole.setSelected(!host);

        hostPortField.setText(hostPort.isEmpty() ? String.valueOf(DEFAULT_PORT) : hostPort);
        guestHostField.setText(connectHost);
        guestPortField.setText(connectPort.isEmpty() ? String.valueOf(DEFAULT_PORT) : connectPort);

        String password = config.value(CoopLauncherConfig.PASSWORD);
        // Prefilling is not the player clearing the field, so the watcher has to stay quiet here or
        // an install with no saved password would never get a generated one.
        writingHostPassword = true;
        try {
            hostPasswordField.setText(password);
        } finally {
            writingHostPassword = false;
        }
        guestPasswordField.setText(password);

        String seed = config.value(CoopLauncherConfig.NEW_GAME_SEED);
        hostSeedField.setText(seed);
        guestSeedField.setText(seed);

        select(portMappingBox, config.value(CoopLauncherConfig.PORT_MAPPING));
        select(hudCornerBox, config.value(CoopLauncherConfig.HUD_CORNER));
        select(sectorSizeBox, config.value(CoopLauncherConfig.SECTOR_SIZE));
        select(sectorAgeBox, config.value(CoopLauncherConfig.SECTOR_AGE));

        String grace = config.value(CoopLauncherConfig.RECONNECT_GRACE_SECONDS).trim();
        boolean graceSet = !grace.isEmpty();
        reconnectGraceDefault.setSelected(!graceSet);
        reconnectGraceSpinner.setEnabled(graceSet);
        if (graceSet) {
            try {
                reconnectGraceSpinner.setValue(Integer.parseInt(grace));
            } catch (NumberFormatException ex) {
                LOG.warn("Ignoring an unreadable reconnect grace in the settings file: " + grace);
                reconnectGraceDefault.setSelected(true);
                reconnectGraceSpinner.setEnabled(false);
            }
        }
        onRoleChanged();
    }

    private void onRoleChanged() {
        boolean host = hostRole.isSelected();
        hostPanel.setVisible(host);
        guestPanel.setVisible(!host);
        closeListener("the role changed");
        if (host) {
            maybeGenerateHostPassword();
        }
        frame.pack();
    }

    /**
     * Fills an empty host password field with a generated one. Does nothing once the player has
     * emptied the field themselves, and nothing when they have already typed something.
     */
    private void maybeGenerateHostPassword() {
        if (hostPasswordCleared || !password(hostPasswordField).isEmpty()) {
            return;
        }
        writingHostPassword = true;
        try {
            hostPasswordField.setText(CoopPasswords.generate());
        } finally {
            writingHostPassword = false;
        }
        // The value never reaches the log. The whole point of it is that only the two players see it.
        LOG.info("Generated a host password of " + CoopPasswords.LENGTH + " characters;"
                + " the value is not logged");
        append("Generated a password for you. Copy invite sends it to your partner. Clear the field"
                + " if you would rather have no password.");
    }

    /** Notices the player emptying the host password field, so nothing refills it afterwards. */
    private final class PasswordWatcher implements javax.swing.event.DocumentListener {

        @Override
        public void insertUpdate(javax.swing.event.DocumentEvent event) {
            check();
        }

        @Override
        public void removeUpdate(javax.swing.event.DocumentEvent event) {
            check();
        }

        @Override
        public void changedUpdate(javax.swing.event.DocumentEvent event) {
            check();
        }

        private void check() {
            if (writingHostPassword || hostPasswordCleared) {
                return;
            }
            if (password(hostPasswordField).isEmpty()) {
                hostPasswordCleared = true;
                LOG.info("The player emptied the host password field; it stays empty for this run");
            }
        }
    }

    private void refreshInstallRows() {
        if (layout == null) {
            return;
        }
        config = CoopLauncherConfig.read(layout.coopOptions());
        installRows = CoopInstallCheck.inspect(layout, config.readError());
        for (CoopInstallCheck.Row row : installRows) {
            LOG.info("Install check " + row);
        }
        renderRows();
        int fails = 0;
        int warns = 0;
        for (CoopInstallCheck.Row row : installRows) {
            if (row.status() == CoopInstallCheck.Status.FAIL) {
                fails++;
            } else if (row.status() == CoopInstallCheck.Status.WARN) {
                warns++;
            }
        }
        append("Install check: " + fails + " problem(s), " + warns + " warning(s).");
        for (CoopInstallCheck.Row row : installRows) {
            if (row.status() != CoopInstallCheck.Status.OK) {
                append("  " + row);
            }
        }
    }

    /** Redraws the install panel from {@link #installRows} plus the update row, if one has landed. */
    private void renderRows() {
        rowsPanel.removeAll();
        for (CoopInstallCheck.Row row : installRows) {
            rowsPanel.add(renderRow(row, null));
        }
        if (updateRow != null) {
            JButton openRelease = null;
            if (!updateUrl.isEmpty()) {
                openRelease = new JButton("Open release page");
                String target = updateUrl;
                openRelease.addActionListener(event -> {
                    LOG.info("Opening the release page " + target);
                    openUrl(target);
                });
            }
            rowsPanel.add(renderRow(updateRow, openRelease));
        }
        rowsPanel.revalidate();
        rowsPanel.repaint();
        frame.pack();
    }

    private JComponent renderRow(CoopInstallCheck.Row row, JComponent trailing) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        JLabel badge = new JLabel(switch (row.status()) {
            case OK -> "OK  ";
            case INFO -> "INFO";
            case WARN -> "WARN";
            case FAIL -> "FAIL";
        });
        badge.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        badge.setForeground(switch (row.status()) {
            case OK -> new java.awt.Color(0x1B, 0x6B, 0x2A);
            case INFO -> new java.awt.Color(0x55, 0x55, 0x55);
            case WARN -> new java.awt.Color(0x8A, 0x5A, 0x00);
            case FAIL -> new java.awt.Color(0xA3, 0x1D, 0x1D);
        });
        panel.add(badge);
        panel.add(Box.createHorizontalStrut(8));
        StringBuilder text = new StringBuilder("<html><b>").append(escape(row.label()))
                .append("</b>: ").append(escape(row.detail()));
        if (!row.fix().isEmpty()) {
            text.append("<br><i>").append(escape(row.fix())).append("</i>");
        }
        text.append("</html>");
        JLabel label = new JLabel(text.toString());
        panel.add(label);
        if (trailing != null) {
            panel.add(Box.createHorizontalStrut(8));
            panel.add(trailing);
        }
        panel.add(Box.createHorizontalGlue());
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    // ---- actions --------------------------------------------------------------------------------

    private void copyInvite() {
        LOG.info("Copy invite pressed");
        String address = publicAddressField.getText().trim();
        if (address.isEmpty()) {
            append("Looking up your public address first.");
            lookUpPublicAddress(this::finishCopyInvite);
            return;
        }
        finishCopyInvite();
    }

    private void finishCopyInvite() {
        String address = publicAddressField.getText().trim();
        if (address.isEmpty()) {
            append("No address to put in the invite. Type one in and press Copy invite again.");
            return;
        }
        Integer port = parsePort(hostPortField.getText(), "port");
        if (port == null) {
            return;
        }
        String seed = hostSeedField.getText().trim();
        String seedProblem = CoopSeeds.validate(seed);
        if (seedProblem != null) {
            fail("That seed is not usable: " + seedProblem + ". Press Generate for a fresh one.");
            return;
        }
        String invite;
        try {
            invite = CoopInvite.format(address, port, seed, password(hostPasswordField));
        } catch (IllegalArgumentException ex) {
            fail("Could not build an invite: " + ex.getMessage());
            return;
        }
        setClipboard(invite);
        LOG.info("Invite copied for " + address + ":" + port + " seed=" + seed
                + " password=" + (password(hostPasswordField).isEmpty() ? "none" : "set"));
        append("Invite copied to the clipboard. Send your partner this line:");
        append("  " + invite);
    }

    private void pasteInvite() {
        LOG.info("Paste invite pressed");
        String text = readClipboard();
        CoopInvite.Parsed parsed = CoopInvite.parse(text);
        if (!parsed.ok()) {
            LOG.warn("Invite could not be parsed: " + parsed.error());
            fail("That is not a usable invite: " + parsed.error());
            return;
        }
        CoopInvite invite = parsed.invite();
        guestHostField.setText(invite.host());
        guestPortField.setText(String.valueOf(invite.port()));
        guestPasswordField.setText(invite.password());
        guestSeedField.setText(invite.seed());
        LOG.info("Invite accepted: " + invite);
        append("Invite read. Host " + invite.host() + ":" + invite.port()
                + (invite.seed().isEmpty() ? ", no seed" : ", seed " + invite.seed())
                + (invite.password().isEmpty() ? ", no password" : ", password set") + ".");
    }

    private void lookUpPublicAddress(Runnable then) {
        LOG.info("Public address lookup started");
        append("Looking up your public address.");
        background.submit(() -> {
            CoopPublicAddress.Lookup result = CoopPublicAddress.lookup();
            SwingUtilities.invokeLater(() -> {
                if (result.ok()) {
                    publicAddressField.setText(result.address());
                    LOG.info("Public address lookup returned " + result.address());
                    append("Your public address is " + result.address()
                            + ". Overwrite it if you connect over a LAN or a VPN.");
                } else {
                    LOG.warn("Public address lookup failed: " + result.error());
                    append(result.error());
                }
                if (then != null) {
                    then.run();
                }
            });
        });
    }

    private void checkMyConnection() {
        Integer port = parsePort(hostPortField.getText(), "port");
        if (port == null) {
            return;
        }
        LOG.info("Check my connection pressed for port " + port);
        checkConnectionButton.setEnabled(false);
        closeListener("a new connection check started");
        append("Checking port " + port + ". This takes a few seconds.");

        boolean mappingEnabled = !"off".equalsIgnoreCase(selected(portMappingBox, "auto"));
        CoopPortMapper mapper = CoopPortMapper.start(port, mappingEnabled, System::currentTimeMillis);
        long started = System.currentTimeMillis();
        AtomicInteger ticks = new AtomicInteger();
        checkTimer = new javax.swing.Timer(CHECK_TICK_MILLIS, null);
        checkTimer.addActionListener(event -> {
            long now = System.currentTimeMillis();
            mapper.tick(now);
            ticks.incrementAndGet();
            boolean timedOut = now - started > CHECK_TIMEOUT_MILLIS;
            if (!mapper.result().finished() && !timedOut) {
                return;
            }
            checkTimer.stop();
            checkTimer = null;
            CoopPortMapper.Result result = mapper.result();
            if (timedOut && !result.finished()) {
                append("The router did not answer within 20 seconds; reporting what is known.");
            }
            LOG.info("Port mapper finished after " + ticks.get() + " ticks: tier=" + result.tier()
                    + " external=" + result.externalEndpoint()
                    + " failure=" + (result.failureText().isEmpty() ? "none" : result.failureText()));
            String report = CoopConnectionDoctor.hostReport(port, result,
                    !password(hostPasswordField).isEmpty(), 1);
            for (String line : report.split("\n", -1)) {
                append(line);
            }
            if (result.mapped() && !result.cgnat() && publicAddressField.getText().trim().isEmpty()) {
                publicAddressField.setText(result.externalAddress());
            }
            // shutdown() drives its own bounded release loop (up to 1.2 s of busy waiting against
            // the injected clock), so it has to leave the event dispatch thread. The mapper is not
            // being ticked any more at this point, so handing it over is a clean transfer.
            background.submit(() -> {
                mapper.shutdown();
                SwingUtilities.invokeLater(() -> {
                    append("Released the router mapping so the game can make its own at startup.");
                    openListener(port);
                    checkConnectionButton.setEnabled(true);
                });
            });
        });
        checkTimer.start();
    }

    private void openListener(int port) {
        try {
            listener = CoopLauncherProbe.HostListener.open(port, launcherVersion);
            LOG.info("Launcher listener open on port " + listener.port());
            append("Waiting for the guest's test on port " + listener.port()
                    + ". This stops when you press Launch.");
        } catch (Exception ex) {
            LOG.warn("Could not hold port " + port + " for the guest's test", ex);
            append("Port " + port + " is already in use, so the guest cannot test against this"
                    + " launcher. Is the game already running?");
        }
    }

    private void testConnection() {
        String host = guestHostField.getText().trim();
        Integer port = parsePort(guestPortField.getText(), "port");
        if (port == null) {
            return;
        }
        LOG.info("Test connection pressed for " + host + ":" + port);
        testConnectionButton.setEnabled(false);
        append("Testing " + host + ":" + port + ".");
        background.submit(() -> {
            CoopLauncherProbe.Result result = CoopLauncherProbe.GuestProber.probe(host, port);
            SwingUtilities.invokeLater(() -> {
                LOG.info("Probe result tcp=" + result.tcpReachable()
                        + " launcher=" + result.launcherAnswered()
                        + " version=" + result.launcherVersion()
                        + " udp=" + result.udpEchoed()
                        + " rtt=" + result.rttMillis());
                append("  TCP reachable       " + yesNo(result.tcpReachable()));
                append("  launcher answered   " + yesNo(result.launcherAnswered())
                        + (result.launcherVersion().isEmpty() ? "" : " (version "
                        + result.launcherVersion() + ")"));
                append("  UDP echoed          " + yesNo(result.udpEchoed()));
                append("  round trip          " + (result.rttMillis() < 0 ? "not measured"
                        : result.rttMillis() + " ms"));
                append(result.message());
                testConnectionButton.setEnabled(true);
            });
        });
    }

    private void saveBugReport() {
        if (layout == null) {
            append("Point the launcher at your Starsector install first.");
            return;
        }
        boolean includeSave = includeSaveBox.isSelected();
        String role = hostRole.isSelected() ? "host" : "guest";
        LOG.info("Save a bug report pressed; role=" + role + " includeSave=" + includeSave);
        bugReportButton.setEnabled(false);
        append("Packing a bug report. A large game log takes a few seconds.");
        CoopInstallLayout target = layout;
        background.submit(() -> {
            CoopBugReport.Result result = null;
            Exception failure = null;
            try {
                result = CoopBugReport.write(target, role, includeSave);
            } catch (Exception ex) {
                failure = ex;
            }
            CoopBugReport.Result finished = result;
            Exception thrown = failure;
            SwingUtilities.invokeLater(() -> {
                bugReportButton.setEnabled(true);
                if (thrown != null) {
                    LOG.error("Could not write the bug report", thrown);
                    fail("Could not write the bug report: " + thrown.getMessage());
                    return;
                }
                LOG.info("Bug report written to " + finished.zip() + " with "
                        + finished.entries().size() + " entries; missing " + finished.missing()
                        + "; notes " + finished.notes());
                append("Saved " + finished.zip() + ". It contains your public address from the"
                        + " doctor block and the last two game logs; attach both players' zips to"
                        + " the report.");
                if (finished.saveInFlight()) {
                    append("The newest save was still being written, run the report again in a"
                            + " moment.");
                }
                openPath(finished.zip().getParentFile());
            });
        });
    }

    /**
     * Asks GitHub whether there is a newer release, on its own thread so a slow answer cannot hold
     * up the worker the buttons use. Every failure lands on a neutral row and nothing else changes.
     */
    private void startUpdateCheck() {
        String version = launcherVersion;
        Thread thread = new Thread(() -> {
            CoopUpdateCheck.Outcome outcome = CoopUpdateCheck.check(version);
            SwingUtilities.invokeLater(() -> {
                updateRow = CoopUpdateCheck.row(outcome);
                updateUrl = outcome.kind() == CoopUpdateCheck.Kind.UPDATE_AVAILABLE
                        ? outcome.url() : "";
                LOG.info("Update check " + updateRow);
                renderRows();
                if (outcome.kind() == CoopUpdateCheck.Kind.UPDATE_AVAILABLE) {
                    append("Version " + outcome.version() + " is out. Both of you have to be on the"
                            + " same release: " + outcome.url());
                }
            });
        }, "coop-launcher-update-check");
        thread.setDaemon(true);
        thread.start();
    }

    private void launch() {
        LOG.info("Launch pressed");
        if (layout == null) {
            fail("Point the launcher at your Starsector install first.");
            return;
        }
        refreshInstallRows();
        if (CoopInstallCheck.blocked(installRows)) {
            List<String> problems = new ArrayList<>();
            for (CoopInstallCheck.Row row : installRows) {
                if (row.status() == CoopInstallCheck.Status.FAIL) {
                    problems.add(row.label() + " - " + row.detail());
                }
            }
            LOG.warn("Launch refused; unresolved problems: " + problems);
            fail("The install still has " + problems.size() + " problem(s) that would stop a co-op"
                    + " session:\n\n" + String.join("\n", problems)
                    + "\n\nFix them and press Refresh.");
            return;
        }

        boolean host = hostRole.isSelected();
        Map<String, String> owned = new LinkedHashMap<>();
        if (host) {
            Integer port = parsePort(hostPortField.getText(), "port");
            if (port == null) {
                return;
            }
            owned.put(CoopLauncherConfig.HOST_PORT, String.valueOf(port));
            owned.put(CoopLauncherConfig.PASSWORD, password(hostPasswordField));
            String seed = hostSeedField.getText().trim();
            String seedProblem = CoopSeeds.validate(seed);
            if (seedProblem != null) {
                fail("That seed is not usable: " + seedProblem);
                return;
            }
            owned.put(CoopLauncherConfig.NEW_GAME_SEED, seed);
        } else {
            String address = guestHostField.getText().trim();
            if (address.isEmpty()) {
                fail("Paste the invite, or type the host's address in.");
                return;
            }
            Integer port = parsePort(guestPortField.getText(), "port");
            if (port == null) {
                return;
            }
            owned.put(CoopLauncherConfig.CONNECT_HOST, address);
            owned.put(CoopLauncherConfig.CONNECT_PORT, String.valueOf(port));
            owned.put(CoopLauncherConfig.PASSWORD, password(guestPasswordField));
            String seed = guestSeedField.getText().trim();
            String seedProblem = CoopSeeds.validate(seed);
            if (seedProblem != null) {
                fail("The seed from the invite is not usable: " + seedProblem);
                return;
            }
            owned.put(CoopLauncherConfig.NEW_GAME_SEED, seed);
        }
        owned.put(CoopLauncherConfig.PORT_MAPPING, selected(portMappingBox, ""));
        owned.put(CoopLauncherConfig.RECONNECT_GRACE_SECONDS,
                reconnectGraceDefault.isSelected() ? "" : String.valueOf(reconnectGraceSpinner.getValue()));
        owned.put(CoopLauncherConfig.HUD_CORNER, selected(hudCornerBox, ""));
        owned.put(CoopLauncherConfig.SECTOR_SIZE, selected(sectorSizeBox, ""));
        owned.put(CoopLauncherConfig.SECTOR_AGE, selected(sectorAgeBox, ""));

        try {
            config.write(layout.coopOptions(), host, owned);
        } catch (Exception ex) {
            LOG.error("Could not write " + layout.coopOptions(), ex);
            fail("Could not save your settings to " + layout.coopOptions() + ":\n\n"
                    + ex.getMessage());
            return;
        }
        LOG.info("Wrote " + layout.coopOptions() + " as " + (host ? "HOST" : "GUEST")
                + " with keys " + owned.keySet());
        append("Settings saved to " + layout.coopOptions() + ".");

        closeListener("the game is starting");
        try {
            gameProcess = CoopGameProcess.launch(layout);
        } catch (Exception ex) {
            LOG.error("Could not start starsector.exe", ex);
            fail("Could not start the game:\n\n" + ex.getMessage());
            return;
        }
        long pid = gameProcess.pid();
        LOG.info("Started starsector.exe pid " + pid);
        append("Starsector started (pid " + pid + "). This window keeps showing the co-op lines from"
                + " the game log.");
        gameProcess.onExit().thenAccept(process -> SwingUtilities.invokeLater(() -> {
            LOG.info("starsector.exe exited with code " + process.exitValue());
            append("Starsector exited (code " + process.exitValue() + ").");
        }));
        startLogTail();
    }

    private void startLogTail() {
        if (logTail != null) {
            logTail.close();
            logTail = null;
        }
        File log = layout.starsectorLog();
        logTail = CoopLogTail.start(log, line -> SwingUtilities.invokeLater(() -> append(line)));
        LOG.info("Tailing " + log);
        append("Tailing " + log + ".");
    }

    private void chooseInstallFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Pick your Starsector folder (the one with starsector.exe in it)");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (layout != null) {
            chooser.setCurrentDirectory(layout.installRoot());
        }
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File chosen = chooser.getSelectedFile();
        LOG.info("Player picked the install folder " + chosen);
        if (!CoopInstallLayout.looksLikeInstall(chosen)) {
            fail(chosen + " does not hold starsector.exe, vmparams and starsector-core, so it is not"
                    + " a Starsector install.");
            return;
        }
        adoptLayout(CoopInstallLayout.ofInstallRoot(chosen));
    }

    private void openInstallDoc() {
        if (layout == null) {
            append("Point the launcher at your Starsector install first.");
            return;
        }
        File doc = layout.installDoc();
        LOG.info("Opening " + doc);
        if (!doc.isFile()) {
            append("INSTALL.md is not in this copy of the mod. It is in the download, at"
                    + " docs/player/INSTALL.md.");
            return;
        }
        openPath(doc);
    }

    private void shutdown() {
        LOG.info("Launcher window closing");
        closeListener("the launcher is closing");
        if (checkTimer != null) {
            checkTimer.stop();
            checkTimer = null;
        }
        if (logTail != null) {
            logTail.close();
            logTail = null;
        }
        background.shutdownNow();
        // The game, if one was started, keeps running on purpose.
        frame.dispose();
    }

    // ---- helpers --------------------------------------------------------------------------------

    private void closeListener(String reason) {
        if (listener == null) {
            return;
        }
        LOG.info("Closing the launcher listener because " + reason);
        listener.close();
        listener = null;
    }

    private void append(String line) {
        String stamped = LocalTime.now().format(CLOCK) + "  " + line;
        statusArea.append(stamped + "\n");
        statusArea.setCaretPosition(statusArea.getDocument().getLength());
    }

    private void fail(String message) {
        append(message.replace("\n\n", " ").replace("\n", " "));
        JOptionPane.showMessageDialog(frame, message, "Starsector Coop Launcher",
                JOptionPane.WARNING_MESSAGE);
    }

    private Integer parsePort(String text, String what) {
        String trimmed = text == null ? "" : text.trim();
        int port;
        try {
            port = Integer.parseInt(trimmed);
        } catch (NumberFormatException ex) {
            fail("The " + what + " has to be a number between 1 and 65535, not \"" + trimmed + "\".");
            return null;
        }
        if (port < 1 || port > 65535) {
            fail("The " + what + " has to be between 1 and 65535.");
            return null;
        }
        return port;
    }

    private static String password(JPasswordField field) {
        char[] value = field.getPassword();
        return value == null ? "" : new String(value);
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    private JComboBox<String> combo(List<String> allowed) {
        List<String> entries = new ArrayList<>();
        entries.add(DEFAULT_ENTRY);
        entries.addAll(allowed);
        JComboBox<String> box = new JComboBox<>(entries.toArray(new String[0]));
        box.setSelectedItem(DEFAULT_ENTRY);
        return box;
    }

    private static void select(JComboBox<String> box, String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            box.setSelectedItem(DEFAULT_ENTRY);
            return;
        }
        for (int i = 0; i < box.getItemCount(); i++) {
            if (trimmed.equalsIgnoreCase(box.getItemAt(i))) {
                box.setSelectedIndex(i);
                return;
            }
        }
        box.setSelectedItem(DEFAULT_ENTRY);
    }

    /** The chosen value, or {@code fallback} when the "shipped default" entry is selected. */
    private static String selected(JComboBox<String> box, String fallback) {
        Object value = box.getSelectedItem();
        String text = value == null ? "" : String.valueOf(value);
        return DEFAULT_ENTRY.equals(text) ? fallback : text;
    }

    private void addRow(JPanel panel, int row, String label, JComponent field, JComponent extra,
                        String note) {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridy = row;

        c.gridx = 0;
        panel.add(new JLabel(label), c);

        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, c);
        c.fill = GridBagConstraints.NONE;

        c.gridx = 2;
        if (extra != null) {
            panel.add(extra, c);
        } else {
            panel.add(Box.createHorizontalStrut(0), c);
        }

        c.gridx = 3;
        c.weightx = 1.0;
        panel.add(new JLabel(note == null ? "" : note), c);
    }

    private static JPanel titled(String title) {
        JPanel panel = new JPanel();
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private void setClipboard(String text) {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new StringSelection(text), null);
        } catch (Exception ex) {
            LOG.warn("Could not write to the clipboard", ex);
            append("Could not write to the clipboard; copy the line above by hand.");
        }
    }

    private String readClipboard() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            Object data = clipboard.getData(DataFlavor.stringFlavor);
            return data == null ? "" : String.valueOf(data);
        } catch (Exception ex) {
            LOG.warn("Could not read the clipboard", ex);
            return "";
        }
    }

    private void openPath(File path) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.OPEN)) {
                    desktop.open(path);
                    return;
                }
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(path.toURI());
                    return;
                }
            }
            append("This system will not open " + path + " for me. Open it by hand.");
        } catch (Exception ex) {
            LOG.warn("Could not open " + path, ex);
            append("Could not open " + path + ": " + ex.getMessage());
        }
    }

    private void openUrl(String url) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(java.net.URI.create(url));
                return;
            }
            setClipboard(url);
            append("This system will not open a browser for me. The address is on your clipboard: "
                    + url);
        } catch (Exception ex) {
            LOG.warn("Could not open " + url, ex);
            append("Could not open " + url + ": " + ex.getMessage());
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
