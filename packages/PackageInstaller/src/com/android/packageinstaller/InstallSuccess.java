package com.android.packageinstaller;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import androidx.annotation.Nullable;

import java.util.List;

public class InstallSuccess extends Activity {
    private static final String LOG_TAG = InstallSuccess.class.getSimpleName();

    @Nullable
    private PackageUtil.AppSnippet mAppSnippet;

    @Nullable
    private String mAppPackageName;

    @Nullable
    private Intent mLaunchIntent;

    @Nullable
    private AlertDialog mDialog;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setFinishOnTouchOutside(true);

        if (getIntent().getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false)) {
            Intent result = new Intent();
            result.putExtra(Intent.EXTRA_INSTALL_RESULT, PackageManager.INSTALL_SUCCEEDED);
            setResult(Activity.RESULT_OK, result);
            finish();
            return;
        }

        Intent intent = getIntent();
        ApplicationInfo appInfo =
                intent.getParcelableExtra(PackageUtil.INTENT_ATTR_APPLICATION_INFO);
        if (appInfo == null) {
            finish();
            return;
        }

        mAppPackageName = appInfo.packageName;
        mAppSnippet = intent.getParcelableExtra(
                PackageInstallerActivity.EXTRA_APP_SNIPPET,
                PackageUtil.AppSnippet.class
        );

        mLaunchIntent = getPackageManager()
                .getLaunchIntentForPackage(mAppPackageName);
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindUi();
    }

    @Override
    protected void onStop() {
        dismissDialog();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        dismissDialog();
        super.onDestroy();
    }

    private void dismissDialog() {
        if (mDialog != null) {
            if (mDialog.isShowing()) {
                mDialog.dismiss();
            }
            mDialog = null;
        }
    }

    private void bindUi() {
        if (mAppSnippet == null || mDialog != null) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setIcon(mAppSnippet.icon);
        builder.setTitle(mAppSnippet.label);
        builder.setView(R.layout.install_content_view);

        builder.setNegativeButton(R.string.done, (d, w) -> finish());
        builder.setOnCancelListener(d -> finish());

        builder.setPositiveButton(R.string.launch, null);

        mDialog = builder.create();
        mDialog.show();

        mDialog.requireViewById(R.id.install_success)
                .setVisibility(View.VISIBLE);

        Button launchButton =
                mDialog.getButton(DialogInterface.BUTTON_POSITIVE);

        boolean canLaunch = false;
        if (mLaunchIntent != null) {
            List<ResolveInfo> list =
                    getPackageManager().queryIntentActivities(mLaunchIntent, 0);
            canLaunch = list != null && !list.isEmpty()
                    && isLauncherActivityEnabled(mLaunchIntent);
        }

        if (canLaunch) {
            launchButton.setOnClickListener(v -> {
                try {
                    startActivity(mLaunchIntent.addFlags(
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                } catch (ActivityNotFoundException | SecurityException e) {
                    Log.e(LOG_TAG, "Could not start activity", e);
                }
                finish();
            });
        } else {
            launchButton.setVisibility(View.GONE);
        }

        Log.i(LOG_TAG, "Finished installing " + mAppPackageName);
    }

    private boolean isLauncherActivityEnabled(Intent intent) {
        if (intent == null || intent.getComponent() == null) {
            return false;
        }
        return getPackageManager().getComponentEnabledSetting(
                intent.getComponent())
                != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }
}
