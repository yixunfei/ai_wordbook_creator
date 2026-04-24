package com.wordbookgen.app;

import com.wordbookgen.ui.WordbookGenFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.util.Arrays;

public class WordbookGenApplication {

    public static void main(String[] args) {
        // The shaded jar keeps one entry point; --cli switches it into headless mode.
        if (isCliMode(args)) {
            System.exit(WordbookGenCli.run(stripCliModeMarker(args)));
            return;
        }

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

    private static boolean isCliMode(String[] args) {
        if (args == null || args.length == 0) {
            return false;
        }
        return Arrays.stream(args).anyMatch(arg -> "--cli".equals(arg) || "cli".equalsIgnoreCase(arg));
    }

    private static String[] stripCliModeMarker(String[] args) {
        if (args == null || args.length == 0) {
            return new String[0];
        }
        return Arrays.stream(args)
                .filter(arg -> !"--cli".equals(arg) && !"cli".equalsIgnoreCase(arg))
                .toArray(String[]::new);
    }
}
