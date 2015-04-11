
package java.util;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.regex.*;
import java.io.*;
import java.math.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.text.*;
import java.util.Locale;

import sun.misc.LRUCache;

public final class Scanner implements Iterator<String>, Closeable {

    private CharBuffer buf;

    private static final int BUFFER_SIZE = 1024; // change to 1024;

    private int position;

    private Matcher matcher;

    private Pattern delimPattern;

    private Pattern hasNextPattern;

    private int hasNextPosition;

    private String hasNextResult;

    private Readable source;

    private boolean sourceClosed = false;

    private boolean needInput = false;

    private boolean skipped = false;

    private int savedScannerPosition = -1;

    private Object typeCache = null;

    private boolean matchValid = false;

    private boolean closed = false;

    private int radix = 10;

    private int defaultRadix = 10;

    private Locale locale = null;

    private LRUCache<String,Pattern> patternCache =
    new LRUCache<String,Pattern>(7) {
        protected Pattern create(String s) {
            return Pattern.compile(s);
        }
        protected boolean hasName(Pattern p, String s) {
            return p.pattern().equals(s);
        }
    };

    private IOException lastException;

    private static Pattern WHITESPACE_PATTERN = Pattern.compile(
                                                "\\p{javaWhitespace}+");

    private static Pattern FIND_ANY_PATTERN = Pattern.compile("(?s).*");

    private static Pattern NON_ASCII_DIGIT = Pattern.compile(
        "[\\p{javaDigit}&&[^0-9]]");


    private String groupSeparator = "\\,";
    private String decimalSeparator = "\\.";
    private String nanString = "NaN";
    private String infinityString = "Infinity";
    private String positivePrefix = "";
    private String negativePrefix = "\\-";
    private String positiveSuffix = "";
    private String negativeSuffix = "";

    private static volatile Pattern boolPattern;
    private static final String BOOLEAN_PATTERN = "true|false";
    private static Pattern boolPattern() {
        Pattern bp = boolPattern;
        if (bp == null)
            boolPattern = bp = Pattern.compile(BOOLEAN_PATTERN,
                                          Pattern.CASE_INSENSITIVE);
        return bp;
    }

    private Pattern integerPattern;
    private String digits = "0123456789abcdefghijklmnopqrstuvwxyz";
    private String non0Digit = "[\\p{javaDigit}&&[^0]]";
    private int SIMPLE_GROUP_INDEX = 5;
    private String buildIntegerPatternString() {
        String radixDigits = digits.substring(0, radix);
        String digit = "((?i)["+radixDigits+"]|\\p{javaDigit})";
        String groupedNumeral = "("+non0Digit+digit+"?"+digit+"?("+
                                groupSeparator+digit+digit+digit+")+)";
        String numeral = "(("+ digit+"++)|"+groupedNumeral+")";
        String javaStyleInteger = "([-+]?(" + numeral + "))";
        String negativeInteger = negativePrefix + numeral + negativeSuffix;
        String positiveInteger = positivePrefix + numeral + positiveSuffix;
        return "("+ javaStyleInteger + ")|(" +
            positiveInteger + ")|(" +
            negativeInteger + ")";
    }
    private Pattern integerPattern() {
        if (integerPattern == null) {
            integerPattern = patternCache.forName(buildIntegerPatternString());
        }
        return integerPattern;
    }

    private static volatile Pattern separatorPattern;
    private static volatile Pattern linePattern;
    private static final String LINE_SEPARATOR_PATTERN =
                                           "\r\n|[\n\r\u2028\u2029\u0085]";
    private static final String LINE_PATTERN = ".*("+LINE_SEPARATOR_PATTERN+")|.+$";

    private static Pattern separatorPattern() {
        Pattern sp = separatorPattern;
        if (sp == null)
            separatorPattern = sp = Pattern.compile(LINE_SEPARATOR_PATTERN);
        return sp;
    }

    private static Pattern linePattern() {
        Pattern lp = linePattern;
        if (lp == null)
            linePattern = lp = Pattern.compile(LINE_PATTERN);
        return lp;
    }

    private Pattern floatPattern;
    private Pattern decimalPattern;
    private void buildFloatAndDecimalPattern() {
        String digit = "([0-9]|(\\p{javaDigit}))";
        String exponent = "([eE][+-]?"+digit+"+)?";
        String groupedNumeral = "("+non0Digit+digit+"?"+digit+"?("+
                                groupSeparator+digit+digit+digit+")+)";
        String numeral = "(("+digit+"++)|"+groupedNumeral+")";
        String decimalNumeral = "("+numeral+"|"+numeral +
            decimalSeparator + digit + "*+|"+ decimalSeparator +
            digit + "++)";
        String nonNumber = "(NaN|"+nanString+"|Infinity|"+
                               infinityString+")";
        String positiveFloat = "(" + positivePrefix + decimalNumeral +
                            positiveSuffix + exponent + ")";
        String negativeFloat = "(" + negativePrefix + decimalNumeral +
                            negativeSuffix + exponent + ")";
        String decimal = "(([-+]?" + decimalNumeral + exponent + ")|"+
            positiveFloat + "|" + negativeFloat + ")";
        String hexFloat =
            "[-+]?0[xX][0-9a-fA-F]*\\.[0-9a-fA-F]+([pP][-+]?[0-9]+)?";
        String positiveNonNumber = "(" + positivePrefix + nonNumber +
                            positiveSuffix + ")";
        String negativeNonNumber = "(" + negativePrefix + nonNumber +
                            negativeSuffix + ")";
        String signedNonNumber = "(([-+]?"+nonNumber+")|" +
                                 positiveNonNumber + "|" +
                                 negativeNonNumber + ")";
        floatPattern = Pattern.compile(decimal + "|" + hexFloat + "|" +
                                       signedNonNumber);
        decimalPattern = Pattern.compile(decimal);
    }
    private Pattern floatPattern() {
        if (floatPattern == null) {
            buildFloatAndDecimalPattern();
        }
        return floatPattern;
    }
    private Pattern decimalPattern() {
        if (decimalPattern == null) {
            buildFloatAndDecimalPattern();
        }
        return decimalPattern;
    }


    private Scanner(Readable source, Pattern pattern) {
        assert source != null : "source should not be null";
        assert pattern != null : "pattern should not be null";
        this.source = source;
        delimPattern = pattern;
        buf = CharBuffer.allocate(BUFFER_SIZE);
        buf.limit(0);
        matcher = delimPattern.matcher(buf);
        matcher.useTransparentBounds(true);
        matcher.useAnchoringBounds(false);
        useLocale(Locale.getDefault(Locale.Category.FORMAT));
    }

    public Scanner(Readable source) {
        this(Objects.requireNonNull(source, "source"), WHITESPACE_PATTERN);
    }

    public Scanner(InputStream source) {
        this(new InputStreamReader(source), WHITESPACE_PATTERN);
    }

    public Scanner(InputStream source, String charsetName) {
        this(makeReadable(Objects.requireNonNull(source, "source"), toCharset(charsetName)),
             WHITESPACE_PATTERN);
    }

    private static Charset toCharset(String csn) {
        Objects.requireNonNull(csn, "charsetName");
        try {
            return Charset.forName(csn);
        } catch (IllegalCharsetNameException|UnsupportedCharsetException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Readable makeReadable(InputStream source, Charset charset) {
        return new InputStreamReader(source, charset);
    }

    public Scanner(File source) throws FileNotFoundException {
        this((ReadableByteChannel)(new FileInputStream(source).getChannel()));
    }

    public Scanner(File source, String charsetName)
        throws FileNotFoundException
    {
        this(Objects.requireNonNull(source), toDecoder(charsetName));
    }

    private Scanner(File source, CharsetDecoder dec)
        throws FileNotFoundException
    {
        this(makeReadable((ReadableByteChannel)(new FileInputStream(source).getChannel()), dec));
    }

    private static CharsetDecoder toDecoder(String charsetName) {
        Objects.requireNonNull(charsetName, "charsetName");
        try {
            return Charset.forName(charsetName).newDecoder();
        } catch (IllegalCharsetNameException|UnsupportedCharsetException unused) {
            throw new IllegalArgumentException(charsetName);
        }
    }

    private static Readable makeReadable(ReadableByteChannel source,
                                         CharsetDecoder dec) {
        return Channels.newReader(source, dec, -1);
    }

    public Scanner(Path source)
        throws IOException
    {
        this(Files.newInputStream(source));
    }

    public Scanner(Path source, String charsetName) throws IOException {
        this(Objects.requireNonNull(source), toCharset(charsetName));
    }

    private Scanner(Path source, Charset charset)  throws IOException {
        this(makeReadable(Files.newInputStream(source), charset));
    }

    public Scanner(String source) {
        this(new StringReader(source), WHITESPACE_PATTERN);
    }

    public Scanner(ReadableByteChannel source) {
        this(makeReadable(Objects.requireNonNull(source, "source")),
             WHITESPACE_PATTERN);
    }

    private static Readable makeReadable(ReadableByteChannel source) {
        return makeReadable(source, Charset.defaultCharset().newDecoder());
    }

    public Scanner(ReadableByteChannel source, String charsetName) {
        this(makeReadable(Objects.requireNonNull(source, "source"), toDecoder(charsetName)),
             WHITESPACE_PATTERN);
    }


    private void saveState() {
        savedScannerPosition = position;
    }

    private void revertState() {
        this.position = savedScannerPosition;
        savedScannerPosition = -1;
        skipped = false;
    }

    private boolean revertState(boolean b) {
        this.position = savedScannerPosition;
        savedScannerPosition = -1;
        skipped = false;
        return b;
    }

    private void cacheResult() {
        hasNextResult = matcher.group();
        hasNextPosition = matcher.end();
        hasNextPattern = matcher.pattern();
    }

    private void cacheResult(String result) {
        hasNextResult = result;
        hasNextPosition = matcher.end();
        hasNextPattern = matcher.pattern();
    }

    private void clearCaches() {
        hasNextPattern = null;
        typeCache = null;
    }

    private String getCachedResult() {
        position = hasNextPosition;
        hasNextPattern = null;
        typeCache = null;
        return hasNextResult;
    }

    private void useTypeCache() {
        if (closed)
            throw new IllegalStateException("Scanner closed");
        position = hasNextPosition;
        hasNextPattern = null;
        typeCache = null;
    }

    private void readInput() {
        if (buf.limit() == buf.capacity())
            makeSpace();

        int p = buf.position();
        buf.position(buf.limit());
        buf.limit(buf.capacity());

        int n = 0;
        try {
            n = source.read(buf);
        } catch (IOException ioe) {
            lastException = ioe;
            n = -1;
        }

        if (n == -1) {
            sourceClosed = true;
            needInput = false;
        }

        if (n > 0)
            needInput = false;

        buf.limit(buf.position());
        buf.position(p);
    }

    private boolean makeSpace() {
        clearCaches();
        int offset = savedScannerPosition == -1 ?
            position : savedScannerPosition;
        buf.position(offset);
        if (offset > 0) {
            buf.compact();
            translateSavedIndexes(offset);
            position -= offset;
            buf.flip();
            return true;
        }
        int newSize = buf.capacity() * 2;
        CharBuffer newBuf = CharBuffer.allocate(newSize);
        newBuf.put(buf);
        newBuf.flip();
        translateSavedIndexes(offset);
        position -= offset;
        buf = newBuf;
        matcher.reset(buf);
        return true;
    }

    private void translateSavedIndexes(int offset) {
        if (savedScannerPosition != -1)
            savedScannerPosition -= offset;
    }

    private void throwFor() {
        skipped = false;
        if ((sourceClosed) && (position == buf.limit()))
            throw new NoSuchElementException();
        else
            throw new InputMismatchException();
    }

    private boolean hasTokenInBuffer() {
        matchValid = false;
        matcher.usePattern(delimPattern);
        matcher.region(position, buf.limit());

        if (matcher.lookingAt())
            position = matcher.end();

        if (position == buf.limit())
            return false;

        return true;
    }

    private String getCompleteTokenInBuffer(Pattern pattern) {
        matchValid = false;

        matcher.usePattern(delimPattern);
        if (!skipped) { // Enforcing only one skip of leading delims
            matcher.region(position, buf.limit());
            if (matcher.lookingAt()) {
                if (matcher.hitEnd() && !sourceClosed) {
                    needInput = true;
                    return null;
                }
                skipped = true;
                position = matcher.end();
            }
        }

        if (position == buf.limit()) {
            if (sourceClosed)
                return null;
            needInput = true;
            return null;
        }


        matcher.region(position, buf.limit());
        boolean foundNextDelim = matcher.find();
        if (foundNextDelim && (matcher.end() == position)) {
            foundNextDelim = matcher.find();
        }
        if (foundNextDelim) {
            if (matcher.requireEnd() && !sourceClosed) {
                needInput = true;
                return null;
            }
            int tokenEnd = matcher.start();
            if (pattern == null) {
                pattern = FIND_ANY_PATTERN;
            }
            matcher.usePattern(pattern);
            matcher.region(position, tokenEnd);
            if (matcher.matches()) {
                String s = matcher.group();
                position = matcher.end();
                return s;
            } else { // Complete token but it does not match
                return null;
            }
        }

        if (sourceClosed) {
            if (pattern == null) {
                pattern = FIND_ANY_PATTERN;
            }
            matcher.usePattern(pattern);
            matcher.region(position, buf.limit());
            if (matcher.matches()) {
                String s = matcher.group();
                position = matcher.end();
                return s;
            }
            return null;
        }

        needInput = true;
        return null;
    }

    private String findPatternInBuffer(Pattern pattern, int horizon) {
        matchValid = false;
        matcher.usePattern(pattern);
        int bufferLimit = buf.limit();
        int horizonLimit = -1;
        int searchLimit = bufferLimit;
        if (horizon > 0) {
            horizonLimit = position + horizon;
            if (horizonLimit < bufferLimit)
                searchLimit = horizonLimit;
        }
        matcher.region(position, searchLimit);
        if (matcher.find()) {
            if (matcher.hitEnd() && (!sourceClosed)) {
                if (searchLimit != horizonLimit) {
                    needInput = true;
                    return null;
                }
                if ((searchLimit == horizonLimit) && matcher.requireEnd()) {
                    needInput = true;
                    return null;
                }
            }
            position = matcher.end();
            return matcher.group();
        }

        if (sourceClosed)
            return null;

        if ((horizon == 0) || (searchLimit != horizonLimit))
            needInput = true;
        return null;
    }

    private String matchPatternInBuffer(Pattern pattern) {
        matchValid = false;
        matcher.usePattern(pattern);
        matcher.region(position, buf.limit());
        if (matcher.lookingAt()) {
            if (matcher.hitEnd() && (!sourceClosed)) {
                needInput = true;
                return null;
            }
            position = matcher.end();
            return matcher.group();
        }

        if (sourceClosed)
            return null;

        needInput = true;
        return null;
    }

    private void ensureOpen() {
        if (closed)
            throw new IllegalStateException("Scanner closed");
    }


    public void close() {
        if (closed)
            return;
        if (source instanceof Closeable) {
            try {
                ((Closeable)source).close();
            } catch (IOException ioe) {
                lastException = ioe;
            }
        }
        sourceClosed = true;
        source = null;
        closed = true;
    }

    public IOException ioException() {
        return lastException;
    }

    public Pattern delimiter() {
        return delimPattern;
    }

    public Scanner useDelimiter(Pattern pattern) {
        delimPattern = pattern;
        return this;
    }

    public Scanner useDelimiter(String pattern) {
        delimPattern = patternCache.forName(pattern);
        return this;
    }

    public Locale locale() {
        return this.locale;
    }

    public Scanner useLocale(Locale locale) {
        if (locale.equals(this.locale))
            return this;

        this.locale = locale;
        DecimalFormat df =
            (DecimalFormat)NumberFormat.getNumberInstance(locale);
        DecimalFormatSymbols dfs = DecimalFormatSymbols.getInstance(locale);

        groupSeparator =   "\\" + dfs.getGroupingSeparator();
        decimalSeparator = "\\" + dfs.getDecimalSeparator();

        nanString = "\\Q" + dfs.getNaN() + "\\E";
        infinityString = "\\Q" + dfs.getInfinity() + "\\E";
        positivePrefix = df.getPositivePrefix();
        if (positivePrefix.length() > 0)
            positivePrefix = "\\Q" + positivePrefix + "\\E";
        negativePrefix = df.getNegativePrefix();
        if (negativePrefix.length() > 0)
            negativePrefix = "\\Q" + negativePrefix + "\\E";
        positiveSuffix = df.getPositiveSuffix();
        if (positiveSuffix.length() > 0)
            positiveSuffix = "\\Q" + positiveSuffix + "\\E";
        negativeSuffix = df.getNegativeSuffix();
        if (negativeSuffix.length() > 0)
            negativeSuffix = "\\Q" + negativeSuffix + "\\E";

        integerPattern = null;
        floatPattern = null;

        return this;
    }

    public int radix() {
        return this.defaultRadix;
    }

    public Scanner useRadix(int radix) {
        if ((radix < Character.MIN_RADIX) || (radix > Character.MAX_RADIX))
            throw new IllegalArgumentException("radix:"+radix);

        if (this.defaultRadix == radix)
            return this;
        this.defaultRadix = radix;
        integerPattern = null;
        return this;
    }

    private void setRadix(int radix) {
        if (this.radix != radix) {
            integerPattern = null;
            this.radix = radix;
        }
    }

    public MatchResult match() {
        if (!matchValid)
            throw new IllegalStateException("No match result available");
        return matcher.toMatchResult();
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("java.util.Scanner");
        sb.append("[delimiters=" + delimPattern + "]");
        sb.append("[position=" + position + "]");
        sb.append("[match valid=" + matchValid + "]");
        sb.append("[need input=" + needInput + "]");
        sb.append("[source closed=" + sourceClosed + "]");
        sb.append("[skipped=" + skipped + "]");
        sb.append("[group separator=" + groupSeparator + "]");
        sb.append("[decimal separator=" + decimalSeparator + "]");
        sb.append("[positive prefix=" + positivePrefix + "]");
        sb.append("[negative prefix=" + negativePrefix + "]");
        sb.append("[positive suffix=" + positiveSuffix + "]");
        sb.append("[negative suffix=" + negativeSuffix + "]");
        sb.append("[NaN string=" + nanString + "]");
        sb.append("[infinity string=" + infinityString + "]");
        return sb.toString();
    }

    public boolean hasNext() {
        ensureOpen();
        saveState();
        while (!sourceClosed) {
            if (hasTokenInBuffer())
                return revertState(true);
            readInput();
        }
        boolean result = hasTokenInBuffer();
        return revertState(result);
    }

    public String next() {
        ensureOpen();
        clearCaches();

        while (true) {
            String token = getCompleteTokenInBuffer(null);
            if (token != null) {
                matchValid = true;
                skipped = false;
                return token;
            }
            if (needInput)
                readInput();
            else
                throwFor();
        }
    }

    public void remove() {
        throw new UnsupportedOperationException();
    }

    public boolean hasNext(String pattern)  {
        return hasNext(patternCache.forName(pattern));
    }

    public String next(String pattern)  {
        return next(patternCache.forName(pattern));
    }

    public boolean hasNext(Pattern pattern) {
        ensureOpen();
        if (pattern == null)
            throw new NullPointerException();
        hasNextPattern = null;
        saveState();

        while (true) {
            if (getCompleteTokenInBuffer(pattern) != null) {
                matchValid = true;
                cacheResult();
                return revertState(true);
            }
            if (needInput)
                readInput();
            else
                return revertState(false);
        }
    }

    public String next(Pattern pattern) {
        ensureOpen();
        if (pattern == null)
            throw new NullPointerException();

        if (hasNextPattern == pattern)
            return getCachedResult();
        clearCaches();

        while (true) {
            String token = getCompleteTokenInBuffer(pattern);
            if (token != null) {
                matchValid = true;
                skipped = false;
                return token;
            }
            if (needInput)
                readInput();
            else
                throwFor();
        }
    }

    public boolean hasNextLine() {
        saveState();

        String result = findWithinHorizon(linePattern(), 0);
        if (result != null) {
            MatchResult mr = this.match();
            String lineSep = mr.group(1);
            if (lineSep != null) {
                result = result.substring(0, result.length() -
                                          lineSep.length());
                cacheResult(result);

            } else {
                cacheResult();
            }
        }
        revertState();
        return (result != null);
    }

    public String nextLine() {
        if (hasNextPattern == linePattern())
            return getCachedResult();
        clearCaches();

        String result = findWithinHorizon(linePattern, 0);
        if (result == null)
            throw new NoSuchElementException("No line found");
        MatchResult mr = this.match();
        String lineSep = mr.group(1);
        if (lineSep != null)
            result = result.substring(0, result.length() - lineSep.length());
        if (result == null)
            throw new NoSuchElementException();
        else
            return result;
    }


    public String findInLine(String pattern) {
        return findInLine(patternCache.forName(pattern));
    }

    public String findInLine(Pattern pattern) {
        ensureOpen();
        if (pattern == null)
            throw new NullPointerException();
        clearCaches();
        int endPosition = 0;
        saveState();
        while (true) {
            String token = findPatternInBuffer(separatorPattern(), 0);
            if (token != null) {
                endPosition = matcher.start();
                break; // up to next newline
            }
            if (needInput) {
                readInput();
            } else {
                endPosition = buf.limit();
                break; // up to end of input
            }
        }
        revertState();
        int horizonForLine = endPosition - position;
        if (horizonForLine == 0)
            return null;
        return findWithinHorizon(pattern, horizonForLine);
    }

    public String findWithinHorizon(String pattern, int horizon) {
        return findWithinHorizon(patternCache.forName(pattern), horizon);
    }

    public String findWithinHorizon(Pattern pattern, int horizon) {
        ensureOpen();
        if (pattern == null)
            throw new NullPointerException();
        if (horizon < 0)
            throw new IllegalArgumentException("horizon < 0");
        clearCaches();

        while (true) {
            String token = findPatternInBuffer(pattern, horizon);
            if (token != null) {
                matchValid = true;
                return token;
            }
            if (needInput)
                readInput();
            else
                break; // up to end of input
        }
        return null;
    }

    public Scanner skip(Pattern pattern) {
        ensureOpen();
        if (pattern == null)
            throw new NullPointerException();
        clearCaches();

        while (true) {
            String token = matchPatternInBuffer(pattern);
            if (token != null) {
                matchValid = true;
                position = matcher.end();
                return this;
            }
            if (needInput)
                readInput();
            else
                throw new NoSuchElementException();
        }
    }

    public Scanner skip(String pattern) {
        return skip(patternCache.forName(pattern));
    }


    public boolean hasNextBoolean()  {
        return hasNext(boolPattern());
    }

    public boolean nextBoolean()  {
        clearCaches();
        return Boolean.parseBoolean(next(boolPattern()));
    }

    public boolean hasNextByte() {
        return hasNextByte(defaultRadix);
    }

    public boolean hasNextByte(int radix) {
        setRadix(radix);
        boolean result = hasNext(integerPattern());
        if (result) { // Cache it
            try {
                String s = (matcher.group(SIMPLE_GROUP_INDEX) == null) ?
                    processIntegerToken(hasNextResult) :
                    hasNextResult;
                typeCache = Byte.parseByte(s, radix);
            } catch (NumberFormatException nfe) {
                result = false;
            }
        }
        return result;
    }

    public byte nextByte() {
         return nextByte(defaultRadix);
    }

    public byte nextByte(int radix) {
        if ((typeCache != null) && (typeCache instanceof Byte)
            && this.radix == radix) {
            byte val = ((Byte)typeCache).byteValue();
            useTypeCache();
            return val;
        }
        setRadix(radix);
        clearCaches();
        try {
            String s = next(integerPattern());
            if (matcher.group(SIMPLE_GROUP_INDEX) == null)
                s = processIntegerToken(s);
            return Byte.parseByte(s, radix);
        } catch (NumberFormatException nfe) {
            position = matcher.start(); // don't skip bad token
            throw new InputMismatchException(nfe.getMessage());
        }
    }

    public boolean hasNextShort() {
        return hasNextShort(defaultRadix);
    }

    public boolean hasNextShort(int radix) {
        setRadix(radix);
        boolean result = hasNext(integerPattern());
        if (result) { // Cache it
            try {
                String s = (matcher.group(SIMPLE_GROUP_INDEX) == null) ?
                    processIntegerToken(hasNextResult) :
                    hasNextResult;
                typeCache = Short.parseShort(s, radix);
            } catch (NumberFormatException nfe) {
                result = false;
            }
        }
        return result;
    }

    public short nextShort() {
        return nextShort(defaultRadix);
    }

    public short nextShort(int radix) {
        if ((typeCache != null) && (typeCache instanceof Short)
            && this.radix == radix) {
            short val = ((Short)typeCache).shortValue();
            useTypeCache();
            return val;
        }
        setRadix(radix);
        clearCaches();
        try {
            String s = next(integerPattern());
            if (matcher.group(SIMPLE_GROUP_INDEX) == null)
                s = processIntegerToken(s);
            return Short.parseShort(s, radix);
        } catch (NumberFormatException nfe) {
            position = matcher.start(); // don't skip bad token
            throw new InputMismatchException(nfe.getMessage());
        }
    }

    public boolean hasNextInt() {
        return hasNextInt(defaultRadix);
    }

    public boolean hasNextInt(int radix) {
        setRadix(radix);
        boolean result = hasNext(integerPattern());
        if (result) { // Cache it
            try {
                String s = (matcher.group(SIMPLE_GROUP_INDEX) == null) ?
                    processIntegerToken(hasNextResult) :
                    hasNextResult;
                typeCache = Integer.parseInt(s, radix);
            } catch (NumberFormatException nfe) {
                result = false;
            }
        }
        return result;
    }

    private String processIntegerToken(String token) {
        String result = token.replaceAll(""+groupSeparator, "");
        boolean isNegative = false;
        int preLen = negativePrefix.length();
        if ((preLen > 0) && result.startsWith(negativePrefix)) {
            isNegative = true;
            result = result.substring(preLen);
        }
        int sufLen = negativeSuffix.length();
        if ((sufLen > 0) && result.endsWith(negativeSuffix)) {
            isNegative = true;
            result = result.substring(result.length() - sufLen,
                                      result.length());
        }
        if (isNegative)
            result = "-" + result;
        return result;
    }

    public int nextInt() {
        return nextInt(defaultRadix);
    }

    public int nextInt(int radix) {
        if ((typeCache != null) && (typeCache instanceof Integer)
            && this.radix == radix) {
            int val = ((Integer)typeCache).intValue();
            useTypeCache();
            return val;
        }
        setRadix(radix);
        clearCaches();
        try {
            String s = next(integerPattern());
            if (matcher.group(SIMPLE_GROUP_INDEX) == null)
                s = processIntegerToken(s);
            return Integer.parseInt(s, radix);
        } catch (NumberFormatException nfe) {
            position = matcher.start(); // don't skip bad token
            throw new InputMismatchException(nfe.getMessage());
        }
    }

    public boolean hasNextLong() {
        return hasNextLong(defaultRadix);
    }

    public boolean hasNextLong(int radix) {
        setRadix(radix);
        boolean result = hasNext(integerPattern());
        if (result) { // Cache it
            try {
                String s = (matcher.group(SIMPLE_GROUP_INDEX) == null) ?
                    processIntegerToken(hasNextResult) :
                    hasNextResult;
                typeCache = Long.parseLong(s, radix);
            } catch (NumberFormatException nfe) {
                result = false;
            }
        }
        return result;
    }

    public long nextLong() {
        return nextLong(defaultRadix);
    }

    public long nextLong(int radix) {
        if ((typeCache != null) && (typeCache instanceof Long)
            && this.radix == radix) {
            long val = ((Long)typeCache).longValue();
            useTypeCache();
            return val;
        }
        setRadix(radix);
        clearCaches();
        try {
            String s = next(integerPattern());
            if (matcher.group(SIMPLE_GROUP_INDEX) == null)
                s = processIntegerToken(s);
            return Long.parseLong(s, radix);
        } catch (NumberFormatException nfe) {
            position = matcher.start(); // don't skip bad token
            throw new InputMismatchException(nfe.getMessage());
        }
    }

    private String processFloatToken(String token) {
        String result = token.replaceAll(groupSeparator, "");
        if (!decimalSeparator.equals("\\."))
            result = result.replaceAll(decimalSeparator, ".");
        boolean isNegative = false;
        int preLen = negativePrefix.length();
        if ((preLen > 0) && result.startsWith(negativePrefix)) {
            isNegative = true;
            result = result.substring(preLen);
        }
        int sufLen = negativeSuffix.length();
        if ((sufLen > 0) && result.endsWith(negativeSuffix)) {
            isNegative = true;
            result = result.substring(result.length() - sufLen,
                                      result.length());
        }
        if (result.equals(nanString))
            result = "NaN";
        if (result.equals(infinityString))
            result = "Infinity";
        if (isNegative)
            result = "-" + result;

        Matcher m = NON_ASCII_DIGIT.matcher(result);
        if (m.find()) {
            StringBuilder inASCII = new StringBuilder();
            for (int i=0; i<result.length(); i++) {
                char nextChar = result.charAt(i);
                if (Character.isDigit(nextChar)) {
                    int d = Character.digit(nextChar, 10);
                    if (d != -1)
                        inASCII.append(d);
                    else
                        inASCII.append(nextChar);
                } else {
                    inASCII.append(nextChar);
                }
            }
            result = inASCII.toString();
        }

        return result;
    }

    public boolean hasNextFloat() {
        setRadix(10);
        boolean result = hasNext(floatPattern());
        if (result) { // Cache it
            try {
                String s = processFloatToken(hasNextResult);
                typeCache = Float.valueOf(Float.parseFloat(s));
            } catch (NumberFormatException nfe) {
                result = false;
            }
        }
        return result;
    }

    public float nextFloat() {
        if ((typeCache != null) && (typeCache instanceof Float)) {
            float val = ((Float)typeCache).floatValue();
            useTypeCache();
            return val;
        }
        setRadix(10);
        clearCaches();
        try {
            return Float.parseFloat(processFloatToken(next(floatPattern())));
        } catch (NumberFormatException nfe) {
            position = matcher.start(); // don't skip bad token
            throw new InputMismatchException(nfe.getMessage());
        }
    }

    public boolean hasNextDouble() {
        setRadix(10);
        boolean result = hasNext(floatPattern());
        if (result) { // Cache it
            try {
                String s = processFloatToken(hasNextResult);
                typeCache = Double.valueOf(Double.parseDouble(s));
            } catch (NumberFormatException nfe) {
                result = false;
            }
        }
        return result;
    }

    public double nextDouble() {
        if ((typeCache != null) && (typeCache instanceof Double)) {
            double val = ((Double)typeCache).doubleValue();
            useTypeCache();
            return val;
        }
        setRadix(10);
        clearCaches();
        try {
            return Double.parseDouble(processFloatToken(next(floatPattern())));
        } catch (NumberFormatException nfe) {
            position = matcher.start(); // don't skip bad token
            throw new InputMismatchException(nfe.getMessage());
        }
    }


    public boolean hasNextBigInteger() {
        return hasNextBigInteger(defaultRadix);
    }

    public boolean hasNextBigInteger(int radix) {
        setRadix(radix);
        boolean result = hasNext(integerPattern());
        if (result) { // Cache it
            try {
                String s = (matcher.group(SIMPLE_GROUP_INDEX) == null) ?
                    processIntegerToken(hasNextResult) :
                    hasNextResult;
                typeCache = new BigInteger(s, radix);
            } catch (NumberFormatException nfe) {
                result = false;
            }
        }
        return result;
    }

    public BigInteger nextBigInteger() {
        return nextBigInteger(defaultRadix);
    }

    public BigInteger nextBigInteger(int radix) {
        if ((typeCache != null) && (typeCache instanceof BigInteger)
            && this.radix == radix) {
            BigInteger val = (BigInteger)typeCache;
            useTypeCache();
            return val;
        }
        setRadix(radix);
        clearCaches();
        try {
            String s = next(integerPattern());
            if (matcher.group(SIMPLE_GROUP_INDEX) == null)
                s = processIntegerToken(s);
            return new BigInteger(s, radix);
        } catch (NumberFormatException nfe) {
            position = matcher.start(); // don't skip bad token
            throw new InputMismatchException(nfe.getMessage());
        }
    }

    public boolean hasNextBigDecimal() {
        setRadix(10);
        boolean result = hasNext(decimalPattern());
        if (result) { // Cache it
            try {
                String s = processFloatToken(hasNextResult);
                typeCache = new BigDecimal(s);
            } catch (NumberFormatException nfe) {
                result = false;
            }
        }
        return result;
    }

    public BigDecimal nextBigDecimal() {
        if ((typeCache != null) && (typeCache instanceof BigDecimal)) {
            BigDecimal val = (BigDecimal)typeCache;
            useTypeCache();
            return val;
        }
        setRadix(10);
        clearCaches();
        try {
            String s = processFloatToken(next(decimalPattern()));
            return new BigDecimal(s);
        } catch (NumberFormatException nfe) {
            position = matcher.start(); // don't skip bad token
            throw new InputMismatchException(nfe.getMessage());
        }
    }

    public Scanner reset() {
        delimPattern = WHITESPACE_PATTERN;
        useLocale(Locale.getDefault(Locale.Category.FORMAT));
        useRadix(10);
        clearCaches();
        return this;
    }
}
