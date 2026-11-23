package com.rpgjr.mirana.service.mail;

/** Builds the HTML shell shared by every Mirana email. Inline styles and tables only, so mail clients render it. */
final class MailTemplate {

    private static final String FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
    private static final String MONO = "ui-monospace,SFMono-Regular,Menlo,Consolas,monospace";

    private MailTemplate() {
    }

    /**
     * @param preheader preview text shown next to the subject in the inbox
     * @param code      six digit code, or null when the mail has none
     * @param linkLabel button label, or null for no button
     * @param linkUrl   button target
     */
    static String render(String preheader, String heading, String intro, String code, String note,
                         String linkLabel, String linkUrl) {
        StringBuilder b = new StringBuilder(4096);
        b.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<meta name=\"color-scheme\" content=\"dark light\"><meta name=\"supported-color-schemes\" content=\"dark light\">")
                .append("<title>").append(esc(heading)).append("</title></head>")
                .append("<body style=\"margin:0;padding:0;background:#0e0e10;\">")
                .append("<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;color:#0e0e10;\">")
                .append(esc(preheader)).append("</div>")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#0e0e10;\"><tr>")
                .append("<td align=\"center\" style=\"padding:40px 16px;\">")
                .append("<table role=\"presentation\" width=\"480\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;max-width:480px;\">")
                // wordmark
                .append("<tr><td style=\"padding:0 4px 24px;font-family:Georgia,'Times New Roman',serif;font-size:15px;")
                .append("letter-spacing:6px;color:#e8e8ea;\">MIRANA</td></tr>")
                // card
                .append("<tr><td style=\"background:#18181b;border:1px solid #2a2a2e;border-radius:16px;padding:36px 32px;\">")
                .append("<h1 style=\"margin:0 0 12px;font-family:").append(FONT)
                .append(";font-size:22px;line-height:1.3;font-weight:600;color:#f4f4f5;\">").append(esc(heading)).append("</h1>")
                .append("<p style=\"margin:0 0 24px;font-family:").append(FONT)
                .append(";font-size:15px;line-height:1.6;color:#a1a1aa;\">").append(esc(intro)).append("</p>");
        if (code != null) {
            b.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>")
                    .append("<td align=\"center\" style=\"background:#0e0e10;border:1px solid #2a2a2e;border-radius:12px;padding:22px 8px;\">")
                    .append("<div style=\"font-family:").append(FONT)
                    .append(";font-size:11px;letter-spacing:2px;text-transform:uppercase;color:#71717a;margin-bottom:10px;\">Your code</div>")
                    .append("<div style=\"font-family:").append(MONO)
                    .append(";font-size:34px;font-weight:700;letter-spacing:10px;color:#ffffff;padding-left:10px;\">")
                    .append(esc(code)).append("</div></td></tr></table>");
        }
        if (linkLabel != null) {
            b.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\"><tr>")
                    .append("<td style=\"background:#f4f4f5;border-radius:10px;\">")
                    .append("<a href=\"").append(esc(linkUrl)).append("\" style=\"display:inline-block;padding:13px 24px;font-family:")
                    .append(FONT).append(";font-size:15px;font-weight:600;color:#0e0e10;text-decoration:none;\">")
                    .append(esc(linkLabel)).append("</a></td></tr></table>");
        }
        b.append("<p style=\"margin:24px 0 0;font-family:").append(FONT)
                .append(";font-size:13px;line-height:1.6;color:#71717a;\">").append(esc(note)).append("</p>")
                .append("</td></tr>")
                // footer
                .append("<tr><td style=\"padding:20px 4px 0;font-family:").append(FONT)
                .append(";font-size:12px;line-height:1.6;color:#52525b;\">")
                .append("You got this because someone used this address on Mirana. ")
                .append("If that was not you, you can ignore this email.</td></tr>")
                .append("</table></td></tr></table></body></html>");
        return b.toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
