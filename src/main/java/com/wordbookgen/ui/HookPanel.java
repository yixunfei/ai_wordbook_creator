package com.wordbookgen.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Single hook editor used by the advanced settings dialog.
 */
class HookPanel extends JPanel {

    private static final int PATH_FIELD_WIDTH = 520;
    private static final int COMBO_WIDTH = 128;
    private static final int SPINNER_WIDTH = 88;

    private static final Path BUILTIN_SCRIPTS_DIR = Path.of("examples", "hooks", "builtin")
            .toAbsolutePath()
            .normalize();
    private static final Path EXAMPLE_SCRIPTS_DIR = Path.of("examples", "hooks")
            .toAbsolutePath()
            .normalize();

    private final JCheckBox enabledCheck = new JCheckBox("启用");
    private final JComboBox<String> languageCombo = new JComboBox<>(new String[]{"PYTHON", "JAVASCRIPT", "LUA", "JAVA"});
    private final JTextField pathField = new JTextField();
    private final JSpinner timeoutSpinner = new JSpinner(new SpinnerNumberModel(30, 1, 600, 1));

    HookPanel(String title) {
        setLayout(new GridBagLayout());
        setBorder(BorderFactory.createTitledBorder(title));
        setPreferredWidth(languageCombo, COMBO_WIDTH);
        setPreferredWidth(timeoutSpinner, SPINNER_WIDTH);
        setPreferredWidth(pathField, PATH_FIELD_WIDTH);

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        c.gridy = 0;
        add(enabledCheck, c);
        c.gridx = 1;
        add(new JLabel("语言"), c);
        c.gridx = 2;
        add(languageCombo, c);
        c.gridx = 3;
        add(new JLabel("超时(秒)"), c);
        c.gridx = 4;
        add(timeoutSpinner, c);

        c.gridx = 0;
        c.gridy = 1;
        add(new JLabel("脚本路径"), c);
        c.gridx = 1;
        c.gridwidth = 3;
        c.weightx = 0;
        add(pathField, c);
        c.gridx = 4;
        c.gridwidth = 1;
        c.weightx = 0;
        JButton browseButton = new JButton("浏览");
        browseButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            File defaultDir = resolveScriptBrowseDirectory(pathField.getText());
            if (defaultDir != null && defaultDir.exists()) {
                chooser.setCurrentDirectory(defaultDir);
            }
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                pathField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        add(browseButton, c);
        c.gridx = 5;
        c.weightx = 1;
        JPanel filler = new JPanel();
        filler.setOpaque(false);
        add(filler, c);
    }

    private void setPreferredWidth(javax.swing.JComponent component, int width) {
        Dimension preferred = component.getPreferredSize();
        component.setPreferredSize(new Dimension(width, preferred.height));
    }

    private File resolveScriptBrowseDirectory(String currentPath) {
        if (currentPath != null && !currentPath.isBlank()) {
            File current = new File(currentPath.trim());
            if (current.exists()) {
                if (current.isDirectory()) {
                    return current;
                }
                File parent = current.getParentFile();
                if (parent != null && parent.exists()) {
                    return parent;
                }
            }
        }

        File builtinDir = BUILTIN_SCRIPTS_DIR.toFile();
        if (builtinDir.exists()) {
            return builtinDir;
        }

        File exampleDir = EXAMPLE_SCRIPTS_DIR.toFile();
        if (exampleDir.exists()) {
            return exampleDir;
        }

        return new File(".").getAbsoluteFile();
    }

    void fill(UiScriptHookItem item) {
        if (item == null) {
            return;
        }
        enabledCheck.setSelected(item.enabled);
        languageCombo.setSelectedItem(item.language == null ? "JAVASCRIPT" : item.language.toUpperCase(Locale.ROOT));
        pathField.setText(item.scriptPath == null ? "" : item.scriptPath);
        timeoutSpinner.setValue(item.timeoutSec <= 0 ? 30 : item.timeoutSec);
    }

    UiScriptHookItem toItem() {
        UiScriptHookItem item = new UiScriptHookItem();
        item.enabled = enabledCheck.isSelected();
        item.language = (String) languageCombo.getSelectedItem();
        item.scriptPath = pathField.getText() == null ? "" : pathField.getText().trim();
        item.timeoutSec = (int) timeoutSpinner.getValue();
        return item;
    }
}
