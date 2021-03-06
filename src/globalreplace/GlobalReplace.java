package globalreplace;

import jwiki.core.ColorLog;
import jwiki.core.Wiki;
import jwiki.util.FL;
import jwiki.util.Tuple;

import java.time.LocalDateTime;
import java.util.ArrayList;

import javax.security.auth.login.LoginException;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

public class GlobalReplace {
    private static Wiki wiki;
    private static final String NAME = "GlobalReplace";
    private static final String COMMONS_PAGE = "Commons:" + NAME;
    private static final String SIGN_UP = COMMONS_PAGE + "/Sign-in";
    private static final byte[] VERSION_NUM = new byte[] { 0, 6, 5 };// {major},{minor},{fix}
    private static final String VERSION = "v" + VERSION_NUM[0] + "."
            + VERSION_NUM[1] + "." + VERSION_NUM[2];
    private static final String TITLE = "GlobalReplace " + VERSION;
    private static final JTextField OLD_TF;
    private static final JTextField NEW_TF;
    private static final JTextField REASON_TF;
    private static final JProgressBar BAR;
    private static final JButton BUTTON;
    public static final boolean USING_CAPTIAL_LINKS = true;

    private static boolean activated;

    static {
        OLD_TF = new JTextField(30);
        NEW_TF = new JTextField(30);
        REASON_TF = new JTextField(30);
        BAR = new JProgressBar(0, 100);
        BUTTON = new JButton("Start/Stop");
        activated = false;
    }

    public static void main(String[] args) throws LoginException {
        wiki = FGUI.login("commons.wikimedia.org");
        checkVersion();
        signup();

        SwingUtilities.invokeLater(() -> createAndShowGUI());
    }

    /**
     * Exit if the user is not running a stable version
     */
    private static void checkVersion() {
        String versionText = wiki.getPageText(COMMONS_PAGE + "/MinimumVersion");
        if (versionText == null || versionText.equals("")) {
            exitWithWaring("Could not check for updates", "Update check failed");
        }

        String[] minVersion = versionText.substring(1).split("\\.", 3);

        // get int representation of three bytes
        int actualVersion = (VERSION_NUM[0] << 16) + (VERSION_NUM[1] << 8)
                + VERSION_NUM[2];
        int minimumVersion = (Byte.parseByte(minVersion[0]) << 16)
                + (Byte.parseByte(minVersion[1]) << 8)
                + Byte.parseByte(minVersion[2]);

        if (minimumVersion <= actualVersion)
            return;

        JTextArea msg = new JTextArea("Current version: " + VERSION + "\n"
                + "Please update the program to version " + versionText
                + " or higher:" + "\n"
                + "https://github.com/Commonists/GlobalReplace/releases/latest"
                + "\nProgram will stop!");
        msg.setFocusable(true);
        exitWithWaring(msg, "Outdated version");
    }

    private static void createAndShowGUI() {
        // Settings
        OLD_TF.setToolTipText("Use Ctrl+v or Command+v to paste text");
        NEW_TF.setToolTipText("Use Ctrl+v or Command+v to paste text");
        REASON_TF.setToolTipText("Enter an optional edit summary");
        BAR.setStringPainted(true);
        BAR.setString(String.format("Hello, %s! :-)", wiki.whoami()));
        BUTTON.addActionListener(e -> new Thread(new GRThread()).start());

        // Create GUI
        JFrame f = FGUI.simpleJFrame(TITLE, 3, true);
        f.getContentPane().add(
                FGUI.buildForm("Replacement", new JLabel("Old Title: "),
                        OLD_TF, new JLabel("New Title: "), NEW_TF, new JLabel(
                                "Summary: "), REASON_TF), "Center");
        f.getContentPane().add(
                FGUI.boxLayout(1, FGUI.simpleJPanel(BUTTON), BAR), "South");
        FGUI.setJFrameVisible(f);
    }

    /**
     * Sign up any new user of this tool; Do nothing if the user already signed
     * up
     */
    private static void signup() {
        String text = wiki.getPageText(SIGN_UP);
        if (text == null || text.equals("")) {
            exitWithWaring("Could not sign up at " + SIGN_UP, "Sign up error");
        }
        final String user = "[[User:" + wiki.whoami() + "|" + wiki.whoami()
                + "]]";
        if (!text.contains(user)) {
            boolean success = wiki.edit(SIGN_UP, text.trim() + "\n#" + user
                    + ", {{subst:#time:d F Y}}", "Signing up via " + TITLE);
            if (!success) {
                exitWithWaring(
                        "You are not allowed to use this tool; Please request permission at "
                                + SIGN_UP, "Missing permission");
            }
        }
    }

    /**
     * Shut down this program after warning the user.
     * 
     * @param message
     *            the message to display; Only if this object is instance of
     *            String, "Program will stop" will be appended
     * @param title
     *            the title of the message dialog
     */
    private static void exitWithWaring(Object message, String title) {
        if (message instanceof String) {
            message = (String) message + ".\nProgram will stop!";
        }
        JOptionPane.showMessageDialog(null, message, title,
                JOptionPane.ERROR_MESSAGE);
        System.exit(0);
    }

    private static synchronized void negateActivated() {
        activated = !activated;
    }

    private static class GRThread implements Runnable {
        private String old_name;
        private String new_name;
        private String reason;
        private String old_name_regex;
        private static final String[] SPECIAL_CHARS = new String[] { "(", ")",
                "[", "]", "{", "}", "^", "-", "=", "$", "!", "|", "?", "*",
                "+", ".", "<", ">" };

        private GRThread() {
            this.old_name = wiki.nss(OLD_TF.getText()).trim();
            this.old_name = this.old_name.replaceAll("_", " ");
            if(USING_CAPTIAL_LINKS)
                this.old_name = firstCharToUpperCase(this.old_name);
            this.new_name = wiki.nss(NEW_TF.getText()).trim().replace("$", "\\$");
            this.reason = REASON_TF.getText().trim().replace("%", "%%")
                    + " ([[%s" + COMMONS_PAGE + "|%s]])";
            this.makeRegex();
        }

        @Override
        public void run() {
            if (!activated) {
                if (!this.sanityCheck()) {
                    return;
                }
                BUTTON.setText("Stop");
                GlobalReplace.negateActivated();
                this.doJob();
                wiki = wiki.getWiki("commons.wikimedia.org");
                BUTTON.setText("Start");
            } else {
                BUTTON.setEnabled(false);
                GlobalReplace.negateActivated();
            }
        }

        private void doJob() {
            BAR.setValue(0);
            BUTTON.setEnabled(false);
            this.setTextFieldState(false);
            ArrayList<Tuple<String, String>> list = FL.mapToList(wiki.globalUsage("File:"
                    + this.old_name));
            BUTTON.setEnabled(true);
            if (list == null || list.size() == 0) {
                BAR.setString(String.format("'%s' is not globally used",
                        this.old_name));
            } else {
                BAR.setMaximum(list.size());
                String domain = null;
                String text = null;
                logReplacement(list.size());
                for (int i = 0; i < list.size(); ++i) {
                    if (!this.updateStatus(i, list.get(i))) {
                        return;
                    }
                    if (domain != list.get(i).y) {
                        domain = list.get(i).y;
                        wiki = wiki.getWiki(domain);
                    }
                    // TODO Remove temporary replace-workaround after jwiki fix
                    text = wiki.getPageText(list.get(i).x.replaceAll("_", " "));
                    if (text == null || text.equals("")) {
                        ColorLog.warn("Could not find text of " + list.get(i).x);
                        System.exit(-1);
                    }
                    Object[] arrobject = new Object[2];
                    arrobject[0] = domain.contains("commons") ? "" : "Commons:";
                    arrobject[1] = TITLE;
                    wiki.edit(
                            list.get(i).x,
                            text.replaceAll(this.old_name_regex, this.new_name),
                            String.format(this.reason, arrobject));
                }
                BAR.setValue(list.size());
                BAR.setString("Done!");
            }
            this.setTextFieldState(true);
            GlobalReplace.negateActivated();
        }

        /**
         * Append the current replacement to the user's log page
         * 
         * @param size
         *            the size of the replacement
         */
        private void logReplacement(int size) {

            LocalDateTime now = LocalDateTime.now();

            String currentYearAndMonth = String.format("%d/%02d", now.getYear(),
                    now.getMonthValue());
            String currentYear = currentYearAndMonth.split("/", 2)[0];
            String logPage = "User:" + wiki.whoami() + "/GlobalReplaceLog/"
                    + currentYearAndMonth;
            String logPageText = wiki.getPageText(logPage);
            if (logPageText == null || logPageText.equals("")) {
                // create new log page
                logPageText = "A list of all replacements done by " + "[[User:"
                        + wiki.whoami() + "|" + wiki.whoami() + "]] in "
                        + "{{subst:#time: F Y}}" + " using " + "[["
                        + COMMONS_PAGE + "|" + NAME + "]].\n"
                        + "[[Category:GlobalReplace Logs in " + currentYear
                        + "]]" + "\n";
            }
            logPageText = logPageText + "\n* " + "[[:File:" + this.old_name
                    + "]]"
                    + " \u2192 " // \u2192 is unicode for →
                    + "[[:File:" + this.new_name + "]] (" + size
                    + " replacements at " + "{{subst:#time: F d}})";
            wiki.edit(logPage, logPageText, "Updating log page via " + TITLE);
        }

        /**
         * Check if the program is still activated and update the progress bar
         * 
         * @param progress
         *            how much work was done already
         * @param tuple
         *            the string tuple with ...
         * @return if the program is activated
         */
        private boolean updateStatus(int progress, Tuple<String, String> tuple) {
            if (!activated) {
                BAR.setValue(0);
                BAR.setString("Interrupted by user");
                this.setTextFieldState(true);
                BUTTON.setEnabled(true);
                return false;
            }
            BAR.setValue(progress);
            BAR.setString(String.format("Edit %s @ %s (%d/%d)", tuple.x,
                    tuple.y, progress, BAR.getMaximum()));
            return true;
        }

        private void setTextFieldState(boolean editable) {
            OLD_TF.setEditable(editable);
            NEW_TF.setEditable(editable);
            REASON_TF.setEditable(editable);
        }

        /**
         * Translate the old name into regex by escaping all special chars
         */
        private void makeRegex() {
            this.old_name_regex = this.old_name;
            for (String s : SPECIAL_CHARS) {
                this.old_name_regex = this.old_name_regex
                        .replace(s, ("\\" + s));
            }
            this.old_name_regex = this.old_name_regex.replaceAll("( |_)",
                    "( |_)");
            if(!USING_CAPTIAL_LINKS)
                return;
            char char0 = this.old_name_regex.charAt(0);
            if (Character.toUpperCase(char0) != Character.toLowerCase(char0))
                this.old_name_regex = "(" + Character.toLowerCase(char0) + "|"
                        + Character.toUpperCase(char0) + ")"
                        + this.old_name_regex.substring(1);
        }

        /**
         * Check if old and new name are valid; Notify user if they are not
         * 
         * @return if the names are valid
         */
        private boolean sanityCheck() {

            boolean status = hasAllowedFileExtension(this.old_name)
                    && hasAllowedFileExtension(this.new_name);
            if (!status) {
                JOptionPane.showMessageDialog(null,
                        "You can only replace valid file names");
            }
            return status;
        }

        /**
         * Determines whether a file is eligible for upload on WMF wikis.
         * See https://commons.wikimedia.org/wiki/Special:MediaStatistics
         * 
         * @param fn
         *            The file name to check.
         * @return True if the file can be uploaded to WMF wikis.
         */
        private static boolean hasAllowedFileExtension(String fn) {
            final String ALLOWED_FILE_TYPES = "(?i).+?\\.(png|gif|jpg|jpeg|xcf|mid|ogg|ogv|oga|svg|djvu|djv|tiff|tif|pdf|webm|flac|wav|opus)";
            return fn.matches(ALLOWED_FILE_TYPES);
        }
    }

    /**
     * Make the first char of the string upper-case
     * 
     * @param string
     *            The string to be tackled
     * @return The string with the first char upper-cased
     */
    public static String firstCharToUpperCase(String string) {
        if (string == null || string.equals(""))
            return string;
        return Character.toUpperCase(string.charAt(0)) + string.substring(1);
    }
}
