package com.amplitude.api;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.LocaleList;
import android.provider.Settings.Secure;
import android.telephony.TelephonyManager;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@SuppressWarnings("MissingPermission")
public class DeviceInfo {

    private static final String TAG = DeviceInfo.class.getName();

    public static final String OS_NAME = "android";

    private static final String SETTING_LIMIT_AD_TRACKING = "limit_ad_tracking";
    private static final String SETTING_ADVERTISING_ID = "advertising_id";

    private boolean locationListening = true;

    private Context context;

    private CachedInfo cachedInfo;

    /**
     * Internal class serves as a cache
     */
    private class CachedInfo {
        private String advertisingId;
        private String country;
        private String versionName;
        private String osName;
        private String osVersion;
        private String brand;
        private String manufacturer;
        private String model;
        private String carrier;
        private String language;
        private boolean limitAdTrackingEnabled;
        private boolean gpsEnabled; // google play services
        private String appSetId;

        private CachedInfo() {
            advertisingId = getAdvertisingId();
            versionName = getVersionName();
            osName = getOsName();
            osVersion = getOsVersion();
            brand = getBrand();
            manufacturer = getManufacturer();
            model = getModel();
            carrier = getCarrier();
            country = getCountry();
            language = getLanguage();
            gpsEnabled = checkGPSEnabled();
            appSetId = getAppSetId();
        }

        /**
         * Internal methods for getting raw information
         */

        private String getVersionName() {
            PackageInfo packageInfo;
            try {
                packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                return packageInfo.versionName;
            } catch (NameNotFoundException e) {

            } catch (Exception e) {
                
            }
            return null;
        }

        private String getOsName() {
            return OS_NAME;
        }

        private String getOsVersion() {
            return Build.VERSION.RELEASE;
        }

        private String getBrand() {
            return Build.BRAND;
        }

        private String getManufacturer() {
            return Build.MANUFACTURER;
        }

        private String getModel() {
            return Build.MODEL;
        }

        private String getCarrier() {
            try {
                TelephonyManager manager = (TelephonyManager) context
                        .getSystemService(Context.TELEPHONY_SERVICE);
                return manager.getNetworkOperatorName();
            } catch (Exception e) {
                // Failed to get network operator name from network
            }
            return null;
        }

        private String getCountry() {
            // This should not be called on the main thread.

            // Prioritize reverse geocode, but until we have a result from that,
            // we try to grab the country from the network, and finally the locale
            String country = getCountryFromLocation();
            if (!Utils.isEmptyString(country)) {
                return country;
            }

            country = getCountryFromNetwork();
            if (!Utils.isEmptyString(country)) {
                return country;
            }
            return getCountryFromLocale();
        }

        private String getCountryFromLocation() {
            if (!isLocationListening()) {
                return null;
            }

            Location recent = getMostRecentLocation();
            if (recent != null) {
                try {
                    if (Geocoder.isPresent()) {
                        Geocoder geocoder = getGeocoder();
                        List<Address> addresses = geocoder.getFromLocation(recent.getLatitude(),
                                recent.getLongitude(), 1);
                        if (addresses != null) {
                            for (Address address : addresses) {
                                if (address != null) {
                                    return address.getCountryCode();
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    // Failed to reverse geocode location
                } catch (NullPointerException e) {
                    // Failed to reverse geocode location
                } catch (NoSuchMethodError e) {
                    // failed to fetch geocoder
                } catch (IllegalArgumentException e) {
                    // Bad lat / lon values can cause Geocoder to throw IllegalArgumentExceptions
                } catch (IllegalStateException e) {
                    // sometimes the location manager is unavailable
                } catch (SecurityException e) {
                    // Customized Android System without Google Play Service Installed
                }
            }
            return null;
        }

        private String getCountryFromNetwork() {
            try {
                TelephonyManager manager = (TelephonyManager) context
                        .getSystemService(Context.TELEPHONY_SERVICE);
                if (manager.getPhoneType() != TelephonyManager.PHONE_TYPE_CDMA) {
                    String country = manager.getNetworkCountryIso();
                    if (country != null) {
                        return country.toUpperCase(Locale.US);
                    }
                }
            } catch (Exception e) {
                // Failed to get country from network
            }
            return null;
        }

        private Locale getLocale() {
            final Configuration configuration = Resources.getSystem().getConfiguration();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                final LocaleList localeList = configuration.getLocales();
                if (localeList.isEmpty()) {
                    return Locale.getDefault();
                } else {
                    return localeList.get(0);
                }
            } else {
                return configuration.locale;
            }
        }

        private String getCountryFromLocale() {
            return getLocale().getCountry();
        }

        private String getLanguage() {
            return getLocale().getLanguage();
        }

        private String getAdvertisingId() {
            // This should not be called on the main thread.
            if ("Amazon".equals(getManufacturer())) {
                return getAndCacheAmazonAdvertisingId();
            } else {
                return getAndCacheGoogleAdvertisingId();
            }
        }

        private String getAppSetId() {
            try {
                Class AppSet = Class
                        .forName("com.google.android.gms.appset.AppSet");
                Method getClient = AppSet.getMethod("getClient", Context.class);
                Object appSetIdClient = getClient.invoke(null, context);
                Method getAppSetIdInfo = appSetIdClient.getClass().getMethod("getAppSetIdInfo");
                Object taskWithAppSetInfo = getAppSetIdInfo.invoke(appSetIdClient);
                Class Tasks = Class.forName("com.google.android.gms.tasks.Tasks");
                Method await = Tasks.getMethod("await", Class.forName("com.google.android.gms.tasks.Task"));
                Object appSetInfo = await.invoke(null, taskWithAppSetInfo);
                Method getId = appSetInfo.getClass().getMethod("getId");
                appSetId = (String) getId.invoke(appSetInfo);
            } catch (ClassNotFoundException e) {
                AmplitudeLog.getLogger().w(TAG, "Google Play Services SDK not found for app set id!");
            } catch (InvocationTargetException e) {
                AmplitudeLog.getLogger().w(TAG, "Google Play Services not available for app set id");
            } catch (Exception e) {
                AmplitudeLog.getLogger().e(TAG, "Encountered an error connecting to Google Play Services for app set id", e);
            }

            return appSetId;
        }

        private String getAndCacheAmazonAdvertisingId() {
            ContentResolver cr = context.getContentResolver();

            limitAdTrackingEnabled = Secure.getInt(cr, SETTING_LIMIT_AD_TRACKING, 0) == 1;
            advertisingId = Secure.getString(cr, SETTING_ADVERTISING_ID);

            return advertisingId;
        }

        private String getAndCacheGoogleAdvertisingId() {
            try {
                Class AdvertisingIdClient = Class
                        .forName("com.google.android.gms.ads.identifier.AdvertisingIdClient");
                Method getAdvertisingInfo = AdvertisingIdClient.getMethod("getAdvertisingIdInfo",
                        Context.class);
                Object advertisingInfo = getAdvertisingInfo.invoke(null, context);
                Method isLimitAdTrackingEnabled = advertisingInfo.getClass().getMethod(
                        "isLimitAdTrackingEnabled");
                Boolean limitAdTrackingEnabled = (Boolean) isLimitAdTrackingEnabled
                        .invoke(advertisingInfo);
                this.limitAdTrackingEnabled =
                        limitAdTrackingEnabled != null && limitAdTrackingEnabled;
                Method getId = advertisingInfo.getClass().getMethod("getId");
                advertisingId = (String) getId.invoke(advertisingInfo);
            } catch (ClassNotFoundException e) {
                AmplitudeLog.getLogger().w(TAG, "Google Play Services SDK not found for advertising id!");
            } catch (InvocationTargetException e) {
                AmplitudeLog.getLogger().w(TAG, "Google Play Services not available for advertising id");
            } catch (Exception e) {
                AmplitudeLog.getLogger().e(TAG, "Encountered an error connecting to Google Play Services for advertising id", e);
            }

            return advertisingId;
        }

        private boolean checkGPSEnabled() {
            // This should not be called on the main thread.
            try {
                Class GPSUtil = Class
                        .forName("com.google.android.gms.common.GooglePlayServicesUtil");
                Method getGPSAvailable = GPSUtil.getMethod("isGooglePlayServicesAvailable",
                        Context.class);
                Integer status = (Integer) getGPSAvailable.invoke(null, context);
                // status 0 corresponds to com.google.android.gms.common.ConnectionResult.SUCCESS;
                return status != null && status.intValue() == 0;
            } catch (NoClassDefFoundError e) {
                AmplitudeLog.getLogger().w(TAG, "Google Play Services Util not found!");
            } catch (ClassNotFoundException e) {
                AmplitudeLog.getLogger().w(TAG, "Google Play Services Util not found!");
            } catch (NoSuchMethodException e) {
                AmplitudeLog.getLogger().w(TAG, "Google Play Services not available");
            } catch (InvocationTargetException e) {
                AmplitudeLog.getLogger().w(TAG, "Google Play Services not available");
            } catch (IllegalAccessException e) {
                AmplitudeLog.getLogger().w(TAG, "Google Play Services not available");
            } catch (Exception e) {
                AmplitudeLog.getLogger().w(TAG,
                        "Error when checking for Google Play Services: " + e);
            }
            return false;
        }
    }

    public DeviceInfo(Context context, boolean locationListening) {
        this.context = context;
        this.locationListening = locationListening;
    }

    private CachedInfo getCachedInfo() {
        if (cachedInfo == null) {
            cachedInfo = new CachedInfo();
        }
        return cachedInfo;
    }

    public void prefetch() {
        getCachedInfo();
    }

    public static String generateUUID() {
        return UUID.randomUUID().toString();
    }

    public String getVersionName() {
        return getCachedInfo().versionName;
    }

    public String getOsName() {
        return getCachedInfo().osName;
    }

    public String getOsVersion() {
        return getCachedInfo().osVersion;
    }

    public String getBrand() {
        return getCachedInfo().brand;
    }

    public String getManufacturer() {
        return getCachedInfo().manufacturer;
    }

    public String getModel() {
        return getCachedInfo().model;
    }

    public String getCarrier() {
        return getCachedInfo().carrier;
    }

    public String getCountry() {
        return getCachedInfo().country;
    }

    public String getLanguage() {
        return getCachedInfo().language;
    }

    public String getAdvertisingId() {
        return getCachedInfo().advertisingId;
    }

    public boolean isLimitAdTrackingEnabled() {
        return getCachedInfo().limitAdTrackingEnabled;
    }

    public String getAppSetId() {
        return getCachedInfo().appSetId;
    }

    public boolean isGooglePlayServicesEnabled() { return getCachedInfo().gpsEnabled; }

    public Location getMostRecentLocation() {
        if (!isLocationListening()) {
            return null;
        }

        if (!Utils.checkLocationPermissionAllowed(context)) {
            return null;
        }

        LocationManager locationManager = (LocationManager) context
                .getSystemService(Context.LOCATION_SERVICE);

        // Don't crash if the device does not have location services.
        if (locationManager == null) {
            return null;
        }

        // It's possible that the location service is running out of process
        // and the remote getProviders call fails. Handle null provider lists.
        List<String> providers = null;
        try {
            providers = locationManager.getProviders(true);
        } catch (SecurityException e) {
            // failed to get providers list
        } catch (Exception e) {
            // other causes
        }
        if (providers == null) {
            return null;
        }

        List<Location> locations = new ArrayList<Location>();
        for (String provider : providers) {
            Location location = null;
            try {
                location = locationManager.getLastKnownLocation(provider);
            } catch (SecurityException e) {
                AmplitudeLog.getLogger().w(TAG, "Failed to get most recent location");
            } catch (Exception e) {
                AmplitudeLog.getLogger().w(TAG, "Failed to get most recent location");
            }
            if (location != null) {
                locations.add(location);
            }
        }

        long maximumTimestamp = -1;
        Location bestLocation = null;
        for (Location location : locations) {
            if (location.getTime() > maximumTimestamp) {
                maximumTimestamp = location.getTime();
                bestLocation = location;
            }
        }

        return bestLocation;
    }

    public boolean isLocationListening() {
        return locationListening;
    }

    public void setLocationListening(boolean locationListening) {
        this.locationListening = locationListening;
    }

    // @VisibleForTesting
    protected Geocoder getGeocoder() {
        return new Geocoder(context, Locale.ENGLISH);
    }

}
