/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.service.quickaccesswallet;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.List;

/**
 * {@link ServiceInfo} and meta-data about a {@link QuickAccessWalletService}.
 *
 * @hide
 */
class QuickAccessWalletServiceInfo {

    private static final String TAG = "QAWalletSInfo";
    private static final String TAG_WALLET_SERVICE = "quickaccesswallet-service";

    private final ServiceInfo mServiceInfo;
    private final ServiceMetadata mServiceMetadata;

    private QuickAccessWalletServiceInfo(
            @NonNull ServiceInfo serviceInfo,
            @NonNull ServiceMetadata metadata) {
        mServiceInfo = serviceInfo;
        mServiceMetadata = metadata;
    }

    @Nullable
    static QuickAccessWalletServiceInfo tryCreate(@NonNull Context context) {
        ComponentName defaultPaymentApp = getDefaultPaymentApp(context);
        if (defaultPaymentApp == null) {
            return null;
        }

        ServiceInfo serviceInfo = getWalletServiceInfo(context, defaultPaymentApp.getPackageName());
        if (serviceInfo == null) {
            return null;
        }

        if (!Manifest.permission.BIND_QUICK_ACCESS_WALLET_SERVICE.equals(serviceInfo.permission)) {
            Log.w(TAG, String.format("%s.%s does not require permission %s",
                    serviceInfo.packageName, serviceInfo.name,
                    Manifest.permission.BIND_QUICK_ACCESS_WALLET_SERVICE));
            return null;
        }

        ServiceMetadata metadata = parseServiceMetadata(context, serviceInfo);
        return new QuickAccessWalletServiceInfo(serviceInfo, metadata);
    }

    private static ComponentName getDefaultPaymentApp(Context context) {
        ContentResolver cr = context.getContentResolver();
        String comp = Settings.Secure.getString(cr, Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT);
        return comp == null ? null : ComponentName.unflattenFromString(comp);
    }

    private static ServiceInfo getWalletServiceInfo(Context context, String packageName) {
        Intent intent = new Intent(QuickAccessWalletService.SERVICE_INTERFACE);
        intent.setPackage(packageName);
        List<ResolveInfo> resolveInfos =
                context.getPackageManager().queryIntentServices(intent,
                        PackageManager.MATCH_DEFAULT_ONLY);
        return resolveInfos.isEmpty() ? null : resolveInfos.get(0).serviceInfo;
    }

    private static class ServiceMetadata {
        @Nullable
        private final String mSettingsActivity;
        @Nullable
        private final String mWalletActivity;

        private ServiceMetadata(String settingsActivity, String walletActivity) {
            this.mSettingsActivity = settingsActivity;
            this.mWalletActivity = walletActivity;
        }
    }

    private static ServiceMetadata parseServiceMetadata(Context context, ServiceInfo serviceInfo) {
        PackageManager pm = context.getPackageManager();
        final XmlResourceParser parser =
                serviceInfo.loadXmlMetaData(pm, QuickAccessWalletService.SERVICE_META_DATA);

        if (parser == null) {
            return new ServiceMetadata(null, null);
        }

        try {
            Resources resources = pm.getResourcesForApplication(serviceInfo.applicationInfo);
            int type = 0;
            while (type != XmlPullParser.END_DOCUMENT && type != XmlPullParser.START_TAG) {
                type = parser.next();
            }

            if (TAG_WALLET_SERVICE.equals(parser.getName())) {
                final AttributeSet allAttributes = Xml.asAttributeSet(parser);
                TypedArray afsAttributes = null;
                try {
                    afsAttributes = resources.obtainAttributes(allAttributes,
                            R.styleable.QuickAccessWalletService);
                    String settingsActivity = afsAttributes.getString(
                            R.styleable.QuickAccessWalletService_settingsActivity);
                    String walletActivity = afsAttributes.getString(
                            R.styleable.QuickAccessWalletService_targetActivity);
                    return new ServiceMetadata(settingsActivity, walletActivity);
                } finally {
                    if (afsAttributes != null) {
                        afsAttributes.recycle();
                    }
                }
            } else {
                Log.e(TAG, "Meta-data does not start with quickaccesswallet-service tag");
            }

        } catch (PackageManager.NameNotFoundException
                | IOException
                | XmlPullParserException e) {
            Log.e(TAG, "Error parsing quickaccesswallet service meta-data", e);
        }
        return new ServiceMetadata(null, null);
    }

    /**
     * @return the component name of the {@link QuickAccessWalletService}
     */
    @NonNull
    ComponentName getComponentName() {
        return mServiceInfo.getComponentName();
    }

    /**
     * @return the fully qualified name of the activity that hosts the full wallet. If available,
     * this intent should be started with the action
     * {@link QuickAccessWalletService#ACTION_VIEW_WALLET}
     */
    @Nullable
    String getWalletActivity() {
        return mServiceMetadata.mWalletActivity;
    }

    /**
     * @return the fully qualified name of the activity that allows the user to change quick access
     * wallet settings. If available, this intent should be started with the action {@link
     * QuickAccessWalletService#ACTION_VIEW_WALLET_SETTINGS}
     */
    @Nullable
    String getSettingsActivity() {
        return mServiceMetadata.mSettingsActivity;
    }
}
