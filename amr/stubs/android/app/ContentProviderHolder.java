package android.app;
import android.content.pm.ProviderInfo;
public class ContentProviderHolder {
    public ProviderInfo info;
    public boolean noReleaseNeeded;
    public boolean mLocal;
    public ContentProviderHolder(ProviderInfo info) { this.info = info; }
}
