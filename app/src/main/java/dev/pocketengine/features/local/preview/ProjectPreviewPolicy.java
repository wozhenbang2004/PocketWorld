package dev.pocketengine.features.local.preview;

/** Per-artifact offline document. This is a restricted preview surface, not a permission sandbox. */
public final class ProjectPreviewPolicy {
    private ProjectPreviewPolicy(){}
    public static final String CSP="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; font-src data:; media-src data:; connect-src 'none'; object-src 'none'; frame-src 'none'; child-src 'none'; worker-src 'none'; form-action 'none'; base-uri 'none'";
    public static String origin(ProjectPreviewArtifact.Verified artifact){return "https://p"+artifact.identity.substring(0,32)+"."+artifact.identity.substring(32)+".invalid/preview.html";}
    public static String document(ProjectPreviewArtifact.Verified artifact) {
        if(!artifact.html)throw new IllegalArgumentException("Only an explicitly opened HTML artifact can execute preview scripts");
        // The restrictive policy precedes all artifact markup. Additional policies cannot relax it.
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\""+CSP+"\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body>"+artifact.content+"</body></html>";
    }
    public static boolean sameDocumentFragment(String url,String documentUrl) {
        return url!=null&&url.startsWith(documentUrl+"#")&&url.length()<=4096;
    }
}
