package adapter.activity;
import android.app.ContentProviderHolder;
import android.app.IApplicationThread;
import android.os.RemoteException;
public class ActivityManagerAdapter {
    public ActivityManagerAdapter() {}
    public ContentProviderHolder getContentProvider(IApplicationThread caller,
            String callingPackage, String name, int userId, boolean stable)
            throws RemoteException { return null; }
    public void attachApplication(IApplicationThread app, long startSeq)
            throws RemoteException {}
}
