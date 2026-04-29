package com.wordbookgen.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;

/**
 * Dialog for system prompt override, debug logging, and hook settings.
 */
class AdvancedSettingsDialog extends JDialog {

    private final JCheckBox overrideCheck = new JCheckBox("启用系统提示词覆盖");
    private final JCheckBox debugCheck = new JCheckBox("调试模式：输出完整模型返回（日志会明显变长）");
    private final JTextArea systemPromptArea = new JTextArea(6, 70);

    private final HookPanel preRequestPanel = new HookPanel("请求前 Hook（PRE_REQUEST）");
    private final HookPanel postResponsePanel = new HookPanel("响应返回 Hook（POST_RESPONSE）");
    private final HookPanel postParsedPanel = new HookPanel("结果解析后 Hook（POST_PARSED）");

    private final AdvancedSettingsState result;
    private boolean confirmed = false;

    AdvancedSettingsDialog(JFrame owner, AdvancedSettingsState current) {
        super(owner, "高级设置", true);
        this.result = current.copy();
        setMinimumSize(new Dimension(760, 560));
        setSize(900, 660);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(8, 8));

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));

        JPanel systemPromptPanel = new JPanel(new BorderLayout());
        systemPromptPanel.setBorder(BorderFactory.createTitledBorder("系统提示词覆盖"));
        overrideCheck.setSelected(current.useSystemPromptOverride);
        debugCheck.setSelected(current.debugMode);
        systemPromptArea.setText(current.systemPromptTemplate == null ? "" : current.systemPromptTemplate);
        systemPromptArea.setLineWrap(true);
        systemPromptArea.setWrapStyleWord(true);
        String help = "可用占位符：{{sourceLanguage}} {{targetLanguage}} {{requiredFields}}";
        JPanel systemTop = new JPanel();
        systemTop.setLayout(new BoxLayout(systemTop, BoxLayout.Y_AXIS));
        systemTop.add(overrideCheck);
        systemTop.add(debugCheck);
        systemPromptPanel.add(systemTop, BorderLayout.NORTH);
        systemPromptPanel.add(new JScrollPane(systemPromptArea), BorderLayout.CENTER);
        systemPromptPanel.add(new JLabel(help), BorderLayout.SOUTH);
        root.add(systemPromptPanel);
        root.add(Box.createVerticalStrut(8));

        preRequestPanel.fill(current.preRequestHook);
        postResponsePanel.fill(current.postResponseHook);
        postParsedPanel.fill(current.postParsedHook);

        root.add(preRequestPanel);
        root.add(Box.createVerticalStrut(6));
        root.add(postResponsePanel);
        root.add(Box.createVerticalStrut(6));
        root.add(postParsedPanel);

        add(new JScrollPane(root), BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton okButton = new JButton("确定");
        JButton cancelButton = new JButton("取消");
        footer.add(okButton);
        footer.add(cancelButton);
        add(footer, BorderLayout.SOUTH);

        okButton.addActionListener(e -> {
            result.useSystemPromptOverride = overrideCheck.isSelected();
            result.debugMode = debugCheck.isSelected();
            result.systemPromptTemplate = systemPromptArea.getText();
            result.preRequestHook = preRequestPanel.toItem();
            result.postResponseHook = postResponsePanel.toItem();
            result.postParsedHook = postParsedPanel.toItem();
            confirmed = true;
            dispose();
        });
        cancelButton.addActionListener(e -> dispose());
    }

    boolean isConfirmed() {
        return confirmed;
    }

    AdvancedSettingsState result() {
        return result;
    }
}
