
package java.util.jar;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.zip.ZipEntry;

import sun.misc.JarIndex;
import sun.security.util.ManifestDigester;
import sun.security.util.ManifestEntryVerifier;
import sun.security.util.SignatureFileVerifier;
import sun.security.util.Debug;

class JarVerifier {

    static final Debug debug = Debug.getInstance("jar");

    private Hashtable<String, CodeSigner[]> verifiedSigners;

    private Hashtable<String, CodeSigner[]> sigFileSigners;

    private Hashtable<String, byte[]> sigFileData;

    private ArrayList<SignatureFileVerifier> pendingBlocks;

    private ArrayList<CodeSigner[]> signerCache;

    private boolean parsingBlockOrSF = false;

    private boolean parsingMeta = true;

    private boolean anyToVerify = true;

    private ByteArrayOutputStream baos;

    private volatile ManifestDigester manDig;

    byte manifestRawBytes[] = null;

    boolean eagerValidation;

    private Object csdomain = new Object();

    private List<Object> manifestDigests;

    public JarVerifier(byte rawBytes[]) {
        manifestRawBytes = rawBytes;
        sigFileSigners = new Hashtable<>();
        verifiedSigners = new Hashtable<>();
        sigFileData = new Hashtable<>(11);
        pendingBlocks = new ArrayList<>();
        baos = new ByteArrayOutputStream();
        manifestDigests = new ArrayList<>();
    }

    public void beginEntry(JarEntry je, ManifestEntryVerifier mev)
        throws IOException
    {
        if (je == null)
            return;

        if (debug != null) {
            debug.println("beginEntry "+je.getName());
        }

        String name = je.getName();


        if (parsingMeta) {
            String uname = name.toUpperCase(Locale.ENGLISH);
            if ((uname.startsWith("META-INF/") ||
                 uname.startsWith("/META-INF/"))) {

                if (je.isDirectory()) {
                    mev.setEntry(null, je);
                    return;
                }

                if (uname.equals(JarFile.MANIFEST_NAME) ||
                        uname.equals(JarIndex.INDEX_NAME)) {
                    return;
                }

                if (SignatureFileVerifier.isBlockOrSF(uname)) {
                    parsingBlockOrSF = true;
                    baos.reset();
                    mev.setEntry(null, je);
                    return;
                }

            }
        }

        if (parsingMeta) {
            doneWithMeta();
        }

        if (je.isDirectory()) {
            mev.setEntry(null, je);
            return;
        }

        if (name.startsWith("./"))
            name = name.substring(2);

        if (name.startsWith("/"))
            name = name.substring(1);

        if (sigFileSigners.get(name) != null ||
                verifiedSigners.get(name) != null) {
            mev.setEntry(name, je);
            return;
        }

        mev.setEntry(null, je);

        return;
    }


    public void update(int b, ManifestEntryVerifier mev)
        throws IOException
    {
        if (b != -1) {
            if (parsingBlockOrSF) {
                baos.write(b);
            } else {
                mev.update((byte)b);
            }
        } else {
            processEntry(mev);
        }
    }


    public void update(int n, byte[] b, int off, int len,
                       ManifestEntryVerifier mev)
        throws IOException
    {
        if (n != -1) {
            if (parsingBlockOrSF) {
                baos.write(b, off, n);
            } else {
                mev.update(b, off, n);
            }
        } else {
            processEntry(mev);
        }
    }

    private void processEntry(ManifestEntryVerifier mev)
        throws IOException
    {
        if (!parsingBlockOrSF) {
            JarEntry je = mev.getEntry();
            if ((je != null) && (je.signers == null)) {
                je.signers = mev.verify(verifiedSigners, sigFileSigners);
                je.certs = mapSignersToCertArray(je.signers);
            }
        } else {

            try {
                parsingBlockOrSF = false;

                if (debug != null) {
                    debug.println("processEntry: processing block");
                }

                String uname = mev.getEntry().getName()
                                             .toUpperCase(Locale.ENGLISH);

                if (uname.endsWith(".SF")) {
                    String key = uname.substring(0, uname.length()-3);
                    byte bytes[] = baos.toByteArray();
                    sigFileData.put(key, bytes);
                    Iterator<SignatureFileVerifier> it = pendingBlocks.iterator();
                    while (it.hasNext()) {
                        SignatureFileVerifier sfv = it.next();
                        if (sfv.needSignatureFile(key)) {
                            if (debug != null) {
                                debug.println(
                                 "processEntry: processing pending block");
                            }

                            sfv.setSignatureFile(bytes);
                            sfv.process(sigFileSigners, manifestDigests);
                        }
                    }
                    return;
                }


                String key = uname.substring(0, uname.lastIndexOf("."));

                if (signerCache == null)
                    signerCache = new ArrayList<>();

                if (manDig == null) {
                    synchronized(manifestRawBytes) {
                        if (manDig == null) {
                            manDig = new ManifestDigester(manifestRawBytes);
                            manifestRawBytes = null;
                        }
                    }
                }

                SignatureFileVerifier sfv =
                  new SignatureFileVerifier(signerCache,
                                            manDig, uname, baos.toByteArray());

                if (sfv.needSignatureFileBytes()) {
                    byte[] bytes = sigFileData.get(key);

                    if (bytes == null) {
                        if (debug != null) {
                            debug.println("adding pending block");
                        }
                        pendingBlocks.add(sfv);
                        return;
                    } else {
                        sfv.setSignatureFile(bytes);
                    }
                }
                sfv.process(sigFileSigners, manifestDigests);

            } catch (IOException ioe) {
                if (debug != null) debug.println("processEntry caught: "+ioe);
            } catch (SignatureException se) {
                if (debug != null) debug.println("processEntry caught: "+se);
            } catch (NoSuchAlgorithmException nsae) {
                if (debug != null) debug.println("processEntry caught: "+nsae);
            } catch (CertificateException ce) {
                if (debug != null) debug.println("processEntry caught: "+ce);
            }
        }
    }

    @Deprecated
    public java.security.cert.Certificate[] getCerts(String name)
    {
        return mapSignersToCertArray(getCodeSigners(name));
    }

    public java.security.cert.Certificate[] getCerts(JarFile jar, JarEntry entry)
    {
        return mapSignersToCertArray(getCodeSigners(jar, entry));
    }

    public CodeSigner[] getCodeSigners(String name)
    {
        return verifiedSigners.get(name);
    }

    public CodeSigner[] getCodeSigners(JarFile jar, JarEntry entry)
    {
        String name = entry.getName();
        if (eagerValidation && sigFileSigners.get(name) != null) {
            try {
                InputStream s = jar.getInputStream(entry);
                byte[] buffer = new byte[1024];
                int n = buffer.length;
                while (n != -1) {
                    n = s.read(buffer, 0, buffer.length);
                }
                s.close();
            } catch (IOException e) {
            }
        }
        return getCodeSigners(name);
    }

    private static java.security.cert.Certificate[] mapSignersToCertArray(
        CodeSigner[] signers) {

        if (signers != null) {
            ArrayList<java.security.cert.Certificate> certChains = new ArrayList<>();
            for (int i = 0; i < signers.length; i++) {
                certChains.addAll(
                    signers[i].getSignerCertPath().getCertificates());
            }

            return certChains.toArray(
                    new java.security.cert.Certificate[certChains.size()]);
        }
        return null;
    }

    boolean nothingToVerify()
    {
        return (anyToVerify == false);
    }

    void doneWithMeta()
    {
        parsingMeta = false;
        anyToVerify = !sigFileSigners.isEmpty();
        baos = null;
        sigFileData = null;
        pendingBlocks = null;
        signerCache = null;
        manDig = null;
        if (sigFileSigners.containsKey(JarFile.MANIFEST_NAME)) {
            CodeSigner[] codeSigners = sigFileSigners.remove(JarFile.MANIFEST_NAME);
            verifiedSigners.put(JarFile.MANIFEST_NAME, codeSigners);
        }
    }

    static class VerifierStream extends java.io.InputStream {

        private InputStream is;
        private JarVerifier jv;
        private ManifestEntryVerifier mev;
        private long numLeft;

        VerifierStream(Manifest man,
                       JarEntry je,
                       InputStream is,
                       JarVerifier jv) throws IOException
        {
            this.is = is;
            this.jv = jv;
            this.mev = new ManifestEntryVerifier(man);
            this.jv.beginEntry(je, mev);
            this.numLeft = je.getSize();
            if (this.numLeft == 0)
                this.jv.update(-1, this.mev);
        }

        public int read() throws IOException
        {
            if (numLeft > 0) {
                int b = is.read();
                jv.update(b, mev);
                numLeft--;
                if (numLeft == 0)
                    jv.update(-1, mev);
                return b;
            } else {
                return -1;
            }
        }

        public int read(byte b[], int off, int len) throws IOException {
            if ((numLeft > 0) && (numLeft < len)) {
                len = (int)numLeft;
            }

            if (numLeft > 0) {
                int n = is.read(b, off, len);
                jv.update(n, b, off, len, mev);
                numLeft -= n;
                if (numLeft == 0)
                    jv.update(-1, b, off, len, mev);
                return n;
            } else {
                return -1;
            }
        }

        public void close()
            throws IOException
        {
            if (is != null)
                is.close();
            is = null;
            mev = null;
            jv = null;
        }

        public int available() throws IOException {
            return is.available();
        }

    }


    private Map<URL, Map<CodeSigner[], CodeSource>> urlToCodeSourceMap = new HashMap<>();
    private Map<CodeSigner[], CodeSource> signerToCodeSource = new HashMap<>();
    private URL lastURL;
    private Map<CodeSigner[], CodeSource> lastURLMap;

    private synchronized CodeSource mapSignersToCodeSource(URL url, CodeSigner[] signers) {
        Map<CodeSigner[], CodeSource> map;
        if (url == lastURL) {
            map = lastURLMap;
        } else {
            map = urlToCodeSourceMap.get(url);
            if (map == null) {
                map = new HashMap<>();
                urlToCodeSourceMap.put(url, map);
            }
            lastURLMap = map;
            lastURL = url;
        }
        CodeSource cs = map.get(signers);
        if (cs == null) {
            cs = new VerifierCodeSource(csdomain, url, signers);
            signerToCodeSource.put(signers, cs);
        }
        return cs;
    }

    private CodeSource[] mapSignersToCodeSources(URL url, List<CodeSigner[]> signers, boolean unsigned) {
        List<CodeSource> sources = new ArrayList<>();

        for (int i = 0; i < signers.size(); i++) {
            sources.add(mapSignersToCodeSource(url, signers.get(i)));
        }
        if (unsigned) {
            sources.add(mapSignersToCodeSource(url, null));
        }
        return sources.toArray(new CodeSource[sources.size()]);
    }
    private CodeSigner[] emptySigner = new CodeSigner[0];

    private CodeSigner[] findMatchingSigners(CodeSource cs) {
        if (cs instanceof VerifierCodeSource) {
            VerifierCodeSource vcs = (VerifierCodeSource) cs;
            if (vcs.isSameDomain(csdomain)) {
                return ((VerifierCodeSource) cs).getPrivateSigners();
            }
        }

        CodeSource[] sources = mapSignersToCodeSources(cs.getLocation(), getJarCodeSigners(), true);
        List<CodeSource> sourceList = new ArrayList<>();
        for (int i = 0; i < sources.length; i++) {
            sourceList.add(sources[i]);
        }
        int j = sourceList.indexOf(cs);
        if (j != -1) {
            CodeSigner[] match;
            match = ((VerifierCodeSource) sourceList.get(j)).getPrivateSigners();
            if (match == null) {
                match = emptySigner;
            }
            return match;
        }
        return null;
    }

    private static class VerifierCodeSource extends CodeSource {
        private static final long serialVersionUID = -9047366145967768825L;

        URL vlocation;
        CodeSigner[] vsigners;
        java.security.cert.Certificate[] vcerts;
        Object csdomain;

        VerifierCodeSource(Object csdomain, URL location, CodeSigner[] signers) {
            super(location, signers);
            this.csdomain = csdomain;
            vlocation = location;
            vsigners = signers; // from signerCache
        }

        VerifierCodeSource(Object csdomain, URL location, java.security.cert.Certificate[] certs) {
            super(location, certs);
            this.csdomain = csdomain;
            vlocation = location;
            vcerts = certs; // from signerCache
        }

        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (obj instanceof VerifierCodeSource) {
                VerifierCodeSource that = (VerifierCodeSource) obj;

                if (isSameDomain(that.csdomain)) {
                    if (that.vsigners != this.vsigners
                            || that.vcerts != this.vcerts) {
                        return false;
                    }
                    if (that.vlocation != null) {
                        return that.vlocation.equals(this.vlocation);
                    } else if (this.vlocation != null) {
                        return this.vlocation.equals(that.vlocation);
                    } else { // both null
                        return true;
                    }
                }
            }
            return super.equals(obj);
        }

        boolean isSameDomain(Object csdomain) {
            return this.csdomain == csdomain;
        }

        private CodeSigner[] getPrivateSigners() {
            return vsigners;
        }

        private java.security.cert.Certificate[] getPrivateCertificates() {
            return vcerts;
        }
    }
    private Map<String, CodeSigner[]> signerMap;

    private synchronized Map<String, CodeSigner[]> signerMap() {
        if (signerMap == null) {
            signerMap = new HashMap<>(verifiedSigners.size() + sigFileSigners.size());
            signerMap.putAll(verifiedSigners);
            signerMap.putAll(sigFileSigners);
        }
        return signerMap;
    }

    public synchronized Enumeration<String> entryNames(JarFile jar, final CodeSource[] cs) {
        final Map<String, CodeSigner[]> map = signerMap();
        final Iterator<Map.Entry<String, CodeSigner[]>> itor = map.entrySet().iterator();
        boolean matchUnsigned = false;

        List<CodeSigner[]> req = new ArrayList<>(cs.length);
        for (int i = 0; i < cs.length; i++) {
            CodeSigner[] match = findMatchingSigners(cs[i]);
            if (match != null) {
                if (match.length > 0) {
                    req.add(match);
                } else {
                    matchUnsigned = true;
                }
            } else {
                matchUnsigned = true;
            }
        }

        final List<CodeSigner[]> signersReq = req;
        final Enumeration<String> enum2 = (matchUnsigned) ? unsignedEntryNames(jar) : emptyEnumeration;

        return new Enumeration<String>() {

            String name;

            public boolean hasMoreElements() {
                if (name != null) {
                    return true;
                }

                while (itor.hasNext()) {
                    Map.Entry<String, CodeSigner[]> e = itor.next();
                    if (signersReq.contains(e.getValue())) {
                        name = e.getKey();
                        return true;
                    }
                }
                while (enum2.hasMoreElements()) {
                    name = enum2.nextElement();
                    return true;
                }
                return false;
            }

            public String nextElement() {
                if (hasMoreElements()) {
                    String value = name;
                    name = null;
                    return value;
                }
                throw new NoSuchElementException();
            }
        };
    }

    public Enumeration<JarEntry> entries2(final JarFile jar, Enumeration<? extends ZipEntry> e) {
        final Map<String, CodeSigner[]> map = new HashMap<>();
        map.putAll(signerMap());
        final Enumeration<? extends ZipEntry> enum_ = e;
        return new Enumeration<JarEntry>() {

            Enumeration<String> signers = null;
            JarEntry entry;

            public boolean hasMoreElements() {
                if (entry != null) {
                    return true;
                }
                while (enum_.hasMoreElements()) {
                    ZipEntry ze = enum_.nextElement();
                    if (JarVerifier.isSigningRelated(ze.getName())) {
                        continue;
                    }
                    entry = jar.newEntry(ze);
                    return true;
                }
                if (signers == null) {
                    signers = Collections.enumeration(map.keySet());
                }
                while (signers.hasMoreElements()) {
                    String name = signers.nextElement();
                    entry = jar.newEntry(new ZipEntry(name));
                    return true;
                }

                return false;
            }

            public JarEntry nextElement() {
                if (hasMoreElements()) {
                    JarEntry je = entry;
                    map.remove(je.getName());
                    entry = null;
                    return je;
                }
                throw new NoSuchElementException();
            }
        };
    }
    private Enumeration<String> emptyEnumeration = new Enumeration<String>() {

        public boolean hasMoreElements() {
            return false;
        }

        public String nextElement() {
            throw new NoSuchElementException();
        }
    };

    static boolean isSigningRelated(String name) {
        return SignatureFileVerifier.isSigningRelated(name);
    }

    private Enumeration<String> unsignedEntryNames(JarFile jar) {
        final Map<String, CodeSigner[]> map = signerMap();
        final Enumeration<JarEntry> entries = jar.entries();
        return new Enumeration<String>() {

            String name;

            public boolean hasMoreElements() {
                if (name != null) {
                    return true;
                }
                while (entries.hasMoreElements()) {
                    String value;
                    ZipEntry e = entries.nextElement();
                    value = e.getName();
                    if (e.isDirectory() || isSigningRelated(value)) {
                        continue;
                    }
                    if (map.get(value) == null) {
                        name = value;
                        return true;
                    }
                }
                return false;
            }

            public String nextElement() {
                if (hasMoreElements()) {
                    String value = name;
                    name = null;
                    return value;
                }
                throw new NoSuchElementException();
            }
        };
    }
    private List<CodeSigner[]> jarCodeSigners;

    private synchronized List<CodeSigner[]> getJarCodeSigners() {
        CodeSigner[] signers;
        if (jarCodeSigners == null) {
            HashSet<CodeSigner[]> set = new HashSet<>();
            set.addAll(signerMap().values());
            jarCodeSigners = new ArrayList<>();
            jarCodeSigners.addAll(set);
        }
        return jarCodeSigners;
    }

    public synchronized CodeSource[] getCodeSources(JarFile jar, URL url) {
        boolean hasUnsigned = unsignedEntryNames(jar).hasMoreElements();

        return mapSignersToCodeSources(url, getJarCodeSigners(), hasUnsigned);
    }

    public CodeSource getCodeSource(URL url, String name) {
        CodeSigner[] signers;

        signers = signerMap().get(name);
        return mapSignersToCodeSource(url, signers);
    }

    public CodeSource getCodeSource(URL url, JarFile jar, JarEntry je) {
        CodeSigner[] signers;

        return mapSignersToCodeSource(url, getCodeSigners(jar, je));
    }

    public void setEagerValidation(boolean eager) {
        eagerValidation = eager;
    }

    public synchronized List<Object> getManifestDigests() {
        return Collections.unmodifiableList(manifestDigests);
    }

    static CodeSource getUnsignedCS(URL url) {
        return new VerifierCodeSource(null, url, (java.security.cert.Certificate[]) null);
    }
}
