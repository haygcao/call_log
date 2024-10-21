package sk.fourq.calllog;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Handler;
import android.provider.CallLog;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@TargetApi(Build.VERSION_CODES.M)
public class CallLogPlugin implements FlutterPlugin, ActivityAware, MethodCallHandler, PluginRegistry.RequestPermissionsResultListener {

    private static final String TAG = "flutter/CALL_LOG";
    private static final String ALREADY_RUNNING = "ALREADY_RUNNING";
    private static final String PERMISSION_NOT_GRANTED = "PERMISSION_NOT_GRANTED";
    private static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    private static final String METHOD_GET = "get";
    private static final String METHOD_QUERY = "query";
    private static final String OPERATOR_LIKE = "LIKE";
    private static final String OPERATOR_GT = ">";
    private static final String OPERATOR_LT = "<";
    private static final String OPERATOR_EQUALS = "=";

    private static final String[] CURSOR_PROJECTION = {
            CallLog.Calls.CACHED_FORMATTED_NUMBER,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.CACHED_NUMBER_TYPE,
            CallLog.Calls.CACHED_NUMBER_LABEL,
            CallLog.Calls.CACHED_MATCHED_NUMBER,
            CallLog.Calls.PHONE_ACCOUNT_ID
    };

    private MethodCall request;
    private Result result;
    private ActivityPluginBinding activityPluginBinding;
    private Activity activity;
    private Context ctx;
    private MethodChannel channel; // 用于与 Flutter 通信的 MethodChannel
    private long lastSyncTimestamp = 0; // 上次同步时间戳

    private void init(BinaryMessenger binaryMessenger, Context applicationContext) {
        Log.d(TAG, "init. Messanger:" + binaryMessenger + " Context:" + applicationContext);
        channel = new MethodChannel(binaryMessenger, "sk.fourq.call_log");
        channel.setMethodCallHandler(this);
        ctx = applicationContext;

        // 获取上次同步时间戳
        SharedPreferences prefs = ctx.getSharedPreferences("call_log_sync", Context.MODE_PRIVATE);
        lastSyncTimestamp = prefs.getLong("last_sync_timestamp", 0);
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        Log.d(TAG, "onAttachedToEngine");
        init(flutterPluginBinding.getBinaryMessenger(), flutterPluginBinding.getApplicationContext());

        // 注册 ContentObserver 监听通话记录的变化
        ctx.getContentResolver().registerContentObserver(CallLog.Calls.CONTENT_URI, true, new CallLogObserver(new Handler(), ctx));
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        //NO-OP
        Log.d(TAG, "onDetachedFromEngine");
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding activityPluginBinding) {
        this.activityPluginBinding = activityPluginBinding;
        activityPluginBinding.addRequestPermissionsResultListener(this);
        activity = activityPluginBinding.getActivity();
        Log.d(TAG, "onAttachedToActivity");
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        Log.d(TAG, "onDetachedFromActivityForConfigChanges");
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding activityPluginBinding) {
        Log.d(TAG, "onReattachedToActivityForConfigChanges");
    }

    @Override
    public void onDetachedFromActivity() {
        Log.d(TAG, "onDetachedFromActivity");
        if (activityPluginBinding != null) {
            activityPluginBinding.removeRequestPermissionsResultListener(this);
            activityPluginBinding = null;
            activity = null;
        }
    }

    @Override
    public void onMethodCall(MethodCall c, Result r) {
        Log.d(TAG, "onMethodCall");
        if (request != null) {
            r.error(ALREADY_RUNNING, "Method call was cancelled. One method call is already running", null);
        }

        request = c;
        result = r;

        String[] perm = {Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE};
        if (hasPermissions(perm)) {
            handleMethodCall();
        } else {
            if (activity != null) {
                ActivityCompat.requestPermissions(activity, perm, 0);
            } else {
                r.error("MISSING_PERMISSIONS", "Permission READ_CALL_LOG or READ_PHONE_STATE is required for plugin. Hovewer, plugin is unable to request permission because of background execution.", null);
            }
        }
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] strings, int[] grantResults) {
        if (requestCode == 0) {
            //CHECK IF ALL REQUESTED PERMISSIONS ARE GRANTED
            for (int grantResult : grantResults) {
                if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                    return false;
                }
            }
            if (request != null) {
                handleMethodCall();
            }
            return true;
        } else {
            if (result != null) {
                result.error(PERMISSION_NOT_GRANTED, null, null);
                cleanup();
            }
            return false;
        }
    }

    /***
     * Handler for flutter {@link MethodCall}
     */
    private void handleMethodCall() {
        switch (request.method) {
            case METHOD_GET:
                queryLogs(null);
                break;
            case METHOD_QUERY:
                String dateFrom = request.argument("dateFrom");
                String dateTo = request.argument("dateTo");
                String durationFrom = request.argument("durationFrom");
                String durationTo = request.argument("durationTo");
                String name = request.argument("name");
                String number = request.argument("number");
                String type = request.argument("type");
                String cachedMatchedNumber = request.argument("cachedMatchedNumber");
                String phoneAccountId = request.argument("phoneAccountId");

                List<String> predicates = new ArrayList<>();
                generatePredicate(predicates, CallLog.Calls.DATE, OPERATOR_GT, dateFrom);
                generatePredicate(predicates, CallLog.Calls.DATE, OPERATOR_LT, dateTo);
                generatePredicate(predicates, CallLog.Calls.DURATION, OPERATOR_GT, durationFrom);
                generatePredicate(predicates, CallLog.Calls.DURATION, OPERATOR_LT, durationTo);
                generatePredicate(predicates, CallLog.Calls.CACHED_NAME, OPERATOR_LIKE, name);
                generatePredicate(predicates, CallLog.Calls.TYPE, OPERATOR_EQUALS, type);
                if (!StringUtils.isEmpty(number)) {
                    List<String> namePredicates = new ArrayList<>();
                    generatePredicate(namePredicates, CallLog.Calls.NUMBER, OPERATOR_LIKE, number);
                    generatePredicate(namePredicates, CallLog.Calls.CACHED_MATCHED_NUMBER, OPERATOR_LIKE, number);
                    generatePredicate(namePredicates, CallLog.Calls.PHONE_ACCOUNT_ID, OPERATOR_LIKE, number);
                    predicates.add("(" + StringUtils.join(namePredicates, " OR ") + ")");
                }
                queryLogs(StringUtils.join(predicates, " AND "));
                break;
            default:
                result.notImplemented();
                cleanup();
        }
    }

    /**
     * Main query method
     *
     * @param query String with sql search condition
     */
    @SuppressLint({"Range", "HardwareIds"})
    private void queryLogs(String query) {
        SubscriptionManager subscriptionManager = ContextCompat.getSystemService(ctx, SubscriptionManager.class);
        TelephonyManager telephonyManager = ContextCompat.getSystemService(ctx, TelephonyManager.class); // Added for carrierName
        List<SubscriptionInfo> subscriptions = null;
        if (subscriptionManager != null) {
            subscriptions = subscriptionManager.getActiveSubscriptionInfoList();
        }

        try (Cursor cursor = ctx.getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                CURSOR_PROJECTION,
                query,
                null,
                CallLog.Calls.DATE + " DESC"
        )) {
            List<HashMap<String, Object>> entries = new ArrayList<>();
            while (cursor != null && cursor.moveToNext()) {
                HashMap<String, Object> map = new HashMap<>();
                map.put("formattedNumber", cursor.getString(0));
                map.put("number", cursor.getString(1));
                map.put("callType", cursor.getInt(2));
                map.put("timestamp", cursor.getLong(3));
                map.put("duration", cursor.getInt(4));
                map.put("name", cursor.getString(5));
                map.put("cachedNumberType", cursor.getInt(6));
                map.put("cachedNumberLabel", cursor.getString(7));
                map.put("cachedMatchedNumber", cursor.getString(8));

                String accountId = cursor.getString(9); // Assuming phoneAccountId is at index 9

                // 获取 SIM 卡槽索引，并加 1
                String simSlotIndex = getSimSlotIndexFromAccountId(ctx, accountId);
                int adjustedSimSlotIndex = Integer.parseInt(simSlotIndex) + 1;

                // 获取 SIM 卡 display name
                String simDisplayName = getSimDisplayName(subscriptions, accountId, telephonyManager);

                map.put("simDisplayName", simDisplayName);
                map.put("simSlotIndex", String.valueOf(adjustedSimSlotIndex)); // 使用调整后的索引
                map.put("phoneAccountId", accountId);
                entries.add(map);
            }
            result.success(entries);
            cleanup();
        } catch (Exception e) {
            result.error(INTERNAL_ERROR, e.getMessage(), null);
            cleanup();
        }
    }

    // 改进后的 getSimDisplayName 方法，参考你提供的代码片段
    private String getSimDisplayName(List<SubscriptionInfo> subscriptions, String accountId, TelephonyManager telephonyManager) {
        if (subscriptions != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                for (SubscriptionInfo info : subscriptions) {
                    if (Integer.toString(info.getSubscriptionId()).equals(accountId)) {
                        return info.getDisplayName().toString();
                    }
                }
            } else {
                // For older APIs, use TelephonyManager or other methods
                // to get SIM display name based on accountId
                // ... (你可以根据需要添加针对旧版本 API 的逻辑)
                return telephonyManager.getSimOperatorName(); // Example for older APIs
            }
        }
        return "Unknown"; // 或其他默认值
    }

    // getSimSlotIndexFromAccountId 方法保持不变
    public static String getSimSlotIndexFromAccountId(Context context, String accountIdToFind) {
        TelecomManager telecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
        for (int index = 0; index < telecomManager.getCallCapablePhoneAccounts().size(); index++) {
            PhoneAccountHandle account = telecomManager.getCallCapablePhoneAccounts().get(index);
            PhoneAccount phoneAccount = telecomManager.getPhoneAccount(account);
            String accountId = phoneAccount.getAccountHandle().getId();
            if (accountIdToFind.equals(accountId)) {
                return String.valueOf(index);
            }
        }
        Integer parsedAccountId = Integer.parseInt(accountIdToFind);
        if (parsedAccountId != null && parsedAccountId >= 0) {
            return String.valueOf(parsedAccountId);
        }
        return "-1";
    }

    // ContentObserver 监听通话记录的变化
    private class CallLogObserver extends ContentObserver {
        private Context context;

        public CallLogObserver(Handler handler, Context context) {
            super(handler);
            this.context = context;
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            // 查询新增的通话记录
            List<HashMap<String, Object>> newEntries = queryNewCallLogs(lastSyncTimestamp);

            // 将新增的通话记录发送给 Flutter 端
            if (!newEntries.isEmpty()) {
                channel.invokeMethod("newCallLogs", newEntries);
            }

            // 更新上次同步时间戳
            lastSyncTimestamp = System.currentTimeMillis();
            SharedPreferences prefs = ctx.getSharedPreferences("call_log_sync", Context.MODE_PRIVATE);
            prefs.edit().putLong("last_sync_timestamp", lastSyncTimestamp).apply();
        }
    }

    // 查询新增的通话记录
    @SuppressLint({"Range", "HardwareIds"})
    private List<HashMap<String, Object>> queryNewCallLogs(long lastTimestamp) {
        List<HashMap<String, Object>> newEntries = new ArrayList<>();

        SubscriptionManager subscriptionManager = ContextCompat.getSystemService(ctx, SubscriptionManager.class);
        TelephonyManager telephonyManager = ContextCompat.getSystemService(ctx, TelephonyManager.class);
        List<SubscriptionInfo> subscriptions = null;
        if (subscriptionManager != null) {
            subscriptions = subscriptionManager.getActiveSubscriptionInfoList();
        }

        String selection = CallLog.Calls.DATE + " > ?";
        String[] selectionArgs = new String[]{String.valueOf(lastTimestamp)};

        try (Cursor cursor = ctx.getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                CURSOR_PROJECTION,
                selection,
                selectionArgs,
                CallLog.Calls.DATE + " ASC"
        )) {
            while (cursor != null && cursor.moveToNext()) {
                HashMap<String, Object> map = new HashMap<>();
                map.put("formattedNumber", cursor.getString(0));
                map.put("number", cursor.getString(1));
                map.put("callType", cursor.getInt(2));
                map.put("timestamp", cursor.getLong(3));
                map.put("duration", cursor.getInt(4));
                map.put("name", cursor.getString(5));
                map.put("cachedNumberType", cursor.getInt(6));
                map.put("cachedNumberLabel", cursor.getString(7));
                map.put("cachedMatchedNumber", cursor.getString(8));

                String accountId = cursor.getString(9); // Assuming phoneAccountId is at index 9

                // 获取 SIM 卡槽索引，并加 1
                String simSlotIndex = getSimSlotIndexFromAccountId(ctx, accountId);
                int adjustedSimSlotIndex = Integer.parseInt(simSlotIndex) + 1;

                // 获取 SIM 卡 display name
                String simDisplayName = getSimDisplayName(subscriptions, accountId, telephonyManager);

                map.put("simDisplayName", simDisplayName);
                map.put("simSlotIndex", String.valueOf(adjustedSimSlotIndex)); // 使用调整后的索引
                map.put("phoneAccountId", accountId);
                newEntries.add(map);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying call logs: " + e.getMessage());
        }

        return newEntries;
    }

    /**
     * Helper method to check if permissions were granted
     *
     * @param permissions Permissions to check
     * @return false, if any permission is not granted, true otherwise
     */
    private boolean hasPermissions(String[] permissions) {
        for (String perm : permissions) {
            if (PackageManager.PERMISSION_GRANTED != ContextCompat.checkSelfPermission(ctx, perm)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Helper method to generate new predicate
     *
     * @param predicates Generated predicate will be appended to this list
     * @param field      Field to search in
     * @param operator   Operator to use for comparision
     * @param value      Value to search for
     */
    private void generatePredicate(List<String> predicates, String field, String operator, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        String escapedValue;
        if (operator.equalsIgnoreCase(OPERATOR_LIKE)) {
            escapedValue = "'%" + value + "%'";
        } else {
            escapedValue = "'" + value + "'";
        }
        predicates.add(field + " " + operator + " " + escapedValue);
    }

    /**
     * Helper method to cleanup after method call
     */
    private void cleanup() {
        request = null;
        result = null;
    }
}