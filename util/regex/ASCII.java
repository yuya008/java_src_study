
package java.util.regex;



final class ASCII {

    static final int UPPER   = 0x00000100;

    static final int LOWER   = 0x00000200;

    static final int DIGIT   = 0x00000400;

    static final int SPACE   = 0x00000800;

    static final int PUNCT   = 0x00001000;

    static final int CNTRL   = 0x00002000;

    static final int BLANK   = 0x00004000;

    static final int HEX     = 0x00008000;

    static final int UNDER   = 0x00010000;

    static final int ASCII   = 0x0000FF00;

    static final int ALPHA   = (UPPER|LOWER);

    static final int ALNUM   = (UPPER|LOWER|DIGIT);

    static final int GRAPH   = (PUNCT|UPPER|LOWER|DIGIT);

    static final int WORD    = (UPPER|LOWER|UNDER|DIGIT);

    static final int XDIGIT  = (HEX);

    private static final int[] ctype = new int[] {
    };

    static int getType(int ch) {
        return ((ch & 0xFFFFFF80) == 0 ? ctype[ch] : 0);
    }

    static boolean isType(int ch, int type) {
        return (getType(ch) & type) != 0;
    }

    static boolean isAscii(int ch) {
        return ((ch & 0xFFFFFF80) == 0);
    }

    static boolean isAlpha(int ch) {
        return isType(ch, ALPHA);
    }

    static boolean isDigit(int ch) {
        return ((ch-'0')|('9'-ch)) >= 0;
    }

    static boolean isAlnum(int ch) {
        return isType(ch, ALNUM);
    }

    static boolean isGraph(int ch) {
        return isType(ch, GRAPH);
    }

    static boolean isPrint(int ch) {
        return ((ch-0x20)|(0x7E-ch)) >= 0;
    }

    static boolean isPunct(int ch) {
        return isType(ch, PUNCT);
    }

    static boolean isSpace(int ch) {
        return isType(ch, SPACE);
    }

    static boolean isHexDigit(int ch) {
        return isType(ch, HEX);
    }

    static boolean isOctDigit(int ch) {
        return ((ch-'0')|('7'-ch)) >= 0;
    }

    static boolean isCntrl(int ch) {
        return isType(ch, CNTRL);
    }

    static boolean isLower(int ch) {
        return ((ch-'a')|('z'-ch)) >= 0;
    }

    static boolean isUpper(int ch) {
        return ((ch-'A')|('Z'-ch)) >= 0;
    }

    static boolean isWord(int ch) {
        return isType(ch, WORD);
    }

    static int toDigit(int ch) {
        return (ctype[ch & 0x7F] & 0x3F);
    }

    static int toLower(int ch) {
        return isUpper(ch) ? (ch + 0x20) : ch;
    }

    static int toUpper(int ch) {
        return isLower(ch) ? (ch - 0x20) : ch;
    }

}
