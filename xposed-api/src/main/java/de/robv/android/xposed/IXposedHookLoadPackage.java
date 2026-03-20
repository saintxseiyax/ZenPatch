package de.robv.android.xposed;

/**
 * Interface for Xposed modules that want to hook into app loading.
 * Implement this interface in your module's entry point class.
 */
public interface IXposedHookLoadPackage {

    /**
     * Called when an application package is loaded.
     * Install your method hooks here.
     * @param lpparam Load package parameters
     */
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
