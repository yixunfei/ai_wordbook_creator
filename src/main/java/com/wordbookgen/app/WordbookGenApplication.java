package com.wordbookgen.app;

import com.wordbookgen.ui.WordbookGenFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public class WordbookGenApplication {

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Keep default look-and-feel when system style is not available.
        }

        SwingUtilities.invokeLater(() -> {
            WordbookGenFrame frame = new WordbookGenFrame();
            frame.setVisible(true);
        });
    }
}
