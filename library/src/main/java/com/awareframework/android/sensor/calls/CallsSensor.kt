package com.awareframework.android.sensor.calls

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.database.ContentObserver
import android.database.Cursor
import android.os.Handler
import android.os.IBinder
import android.provider.CallLog
import android.provider.CallLog.Calls.*
import android.support.v4.content.ContextCompat
import android.telephony.PhoneStateListener
import android.telephony.PhoneStateListener.LISTEN_CALL_STATE
import android.telephony.PhoneStateListener.LISTEN_NONE
import android.telephony.TelephonyManager
import android.telephony.TelephonyManager.*
import android.util.Log
import com.awareframework.android.core.AwareSensor
import com.awareframework.android.core.model.SensorConfig
import com.awareframework.android.sensor.calls.model.CallData


/**
 * Calls Module. For now, scans and returns surrounding calls devices and RSSI dB values.
 *
 * @author  sercant
 * @date 14/08/2018
 */
class CallsSensor : AwareSensor() {

    companion object {
        const val TAG = "AWARECallsSensor"

        /**
         * Fired event: call accepted by the user
         */
        const val ACTION_AWARE_CALL_ACCEPTED = "ACTION_AWARE_CALL_ACCEPTED"

        /**
         * Fired event: phone is ringing
         */
        const val ACTION_AWARE_CALL_RINGING = "ACTION_AWARE_CALL_RINGING"

        /**
         * Fired event: call unanswered
         */
        const val ACTION_AWARE_CALL_MISSED = "ACTION_AWARE_CALL_MISSED"

        /**
         * Fired event: call attempt by the user
         */
        const val ACTION_AWARE_CALL_MADE = "ACTION_AWARE_CALL_MADE"

        /**
         * Fired event: user IS in a call at the moment
         */
        const val ACTION_AWARE_USER_IN_CALL = "ACTION_AWARE_USER_IN_CALL"

        /**
         * Fired event: user is NOT in a call
         */
        const val ACTION_AWARE_USER_NOT_IN_CALL = "ACTION_AWARE_USER_NOT_IN_CALL"

        const val ACTION_AWARE_CALLS_START = "com.awareframework.android.sensor.calls.SENSOR_START"
        const val ACTION_AWARE_CALLS_STOP = "com.awareframework.android.sensor.calls.SENSOR_STOP"

        const val ACTION_AWARE_CALLS_SET_LABEL = "com.awareframework.android.sensor.calls.SET_LABEL"
        const val EXTRA_LABEL = "label"

        const val ACTION_AWARE_CALLS_SYNC = "com.awareframework.android.sensor.calls.SENSOR_SYNC"

        val CONFIG = CallsConfig()

        val REQUIRED_PERMISSIONS = arrayOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG
        )

        fun startService(context: Context, config: CallsConfig? = null) {
            if (config != null)
                CONFIG.replaceWith(config)
            context.startService(Intent(context, CallsSensor::class.java))
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, CallsSensor::class.java))
        }
    }

    private lateinit var telephonyManager: TelephonyManager

    private val callsHandler = Handler()
    private val callsObserver = object : ContentObserver(callsHandler) {

        @SuppressLint("MissingPermission")
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)

            val lastCall: Cursor? = contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null, CallLog.Calls.DATE + " DESC LIMIT 1")


            if (lastCall?.moveToFirst() == true) {
                val lcType = lastCall.getInt(lastCall.getColumnIndex(CallLog.Calls.TYPE))
                val lcTimestamp = lastCall.getLong(lastCall.getColumnIndex(CallLog.Calls.DATE))
                val lcDuration = lastCall.getInt(lastCall.getColumnIndex(CallLog.Calls.DURATION))
                val lcTrace = lastCall.getString(lastCall.getColumnIndex(CallLog.Calls.NUMBER))

                if (!lastCall.isClosed)
                    lastCall.close()

                // TODO add check if the call exists in the db

                val data = CallData().apply {
                    timestamp = System.currentTimeMillis()
                    deviceId = CONFIG.deviceId
                    label = CONFIG.label

                    eventTimestamp = lcTimestamp
                    duration = lcDuration
                    trace = lcTrace // TODO encrypt data
                    type = lcType
                }

                dbEngine?.save(data, CallData.TABLE_NAME)

                when (lcType) {
                    INCOMING_TYPE -> {
                        logd(ACTION_AWARE_CALL_ACCEPTED)

                        CONFIG.sensorObserver?.onCall(data)
                        sendBroadcast(Intent(ACTION_AWARE_CALL_ACCEPTED))
                    }

                    MISSED_TYPE -> {
                        logd(ACTION_AWARE_CALL_MISSED)

                        CONFIG.sensorObserver?.onCall(data)
                        sendBroadcast(Intent(ACTION_AWARE_CALL_MISSED))
                    }

                    OUTGOING_TYPE -> {
                        logd(ACTION_AWARE_CALL_MADE)

                        CONFIG.sensorObserver?.onCall(data)
                        sendBroadcast(Intent(ACTION_AWARE_CALL_MADE))
                    }
                }
            }
        }
    }

    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, incomingNumber: String?) {
            super.onCallStateChanged(state, incomingNumber)

            when (state) {
                CALL_STATE_RINGING -> {
                    logd(ACTION_AWARE_CALL_RINGING)

                    sendBroadcast(Intent(ACTION_AWARE_CALL_RINGING))
                    CONFIG.sensorObserver?.onRinging(incomingNumber)
                }

                CALL_STATE_OFFHOOK -> {
                    logd(ACTION_AWARE_USER_IN_CALL)

                    sendBroadcast(Intent(ACTION_AWARE_USER_IN_CALL))
                    CONFIG.sensorObserver?.onBusy(incomingNumber)
                }

                CALL_STATE_IDLE -> {
                    logd(ACTION_AWARE_USER_NOT_IN_CALL)

                    sendBroadcast(Intent(ACTION_AWARE_USER_NOT_IN_CALL))
                    CONFIG.sensorObserver?.onFree(incomingNumber)
                }
            }
        }
    }


    private val callsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return

            when (intent.action) {
                ACTION_AWARE_CALLS_SET_LABEL -> {
                    intent.getStringExtra(EXTRA_LABEL)?.let {
                        CONFIG.label = it
                    }
                }

                ACTION_AWARE_CALLS_SYNC -> onSync(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        initializeDbEngine(CONFIG)

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        registerReceiver(callsReceiver, IntentFilter().apply {
            addAction(ACTION_AWARE_CALLS_SET_LABEL)
            addAction(ACTION_AWARE_CALLS_SYNC)
        })

        logd("Calls service created!")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (REQUIRED_PERMISSIONS.any { ContextCompat.checkSelfPermission(this, it) != PERMISSION_GRANTED }) {
            logw("Missing permissions detected.")
            return START_NOT_STICKY
        }

        contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, callsObserver)
        telephonyManager.listen(phoneStateListener, LISTEN_CALL_STATE)

        logd("Calls service is active.")

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        contentResolver.unregisterContentObserver(callsObserver)
        telephonyManager.listen(phoneStateListener, LISTEN_NONE)

        unregisterReceiver(callsReceiver)

        dbEngine?.close()

        logd("Calls service terminated.")
    }

    override fun onSync(intent: Intent?) {
        dbEngine?.startSync(CallData.TABLE_NAME)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    data class CallsConfig(
            var sensorObserver: SensorObserver? = null
    ) : SensorConfig(dbPath = "aware_calls") {

        override fun <T : SensorConfig> replaceWith(config: T) {
            super.replaceWith(config)

            if (config is CallsConfig) {
                sensorObserver = config.sensorObserver
            }
        }
    }

    interface SensorObserver {
        /**
         * Callback when a call event is recorded (received, made, missed)
         *
         * @param data
         */
        fun onCall(data: CallData)

        /**
         * Callback when the phone is ringing
         *
         * @param number
         */
        fun onRinging(number: String?)

        /**
         * Callback when the user answered and is busy with a call
         *
         * @param number
         */
        fun onBusy(number: String?)

        /**
         * Callback when the user hangup an ongoing call and is now free
         *
         * @param number
         */
        fun onFree(number: String?)
    }

    class CallsSensorBroadcastReceiver : AwareSensor.SensorBroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {
            context ?: return

            logd("Sensor broadcast received. action: " + intent?.action)

            when (intent?.action) {
                SENSOR_START_ENABLED -> {
                    logd("Sensor enabled: " + CONFIG.enabled)

                    if (CONFIG.enabled) {
                        startService(context)
                    }
                }

                ACTION_AWARE_CALLS_STOP,
                SENSOR_STOP_ALL -> {
                    logd("Stopping sensor.")
                    stopService(context)
                }

                ACTION_AWARE_CALLS_START -> {
                    startService(context)
                }
            }
        }
    }
}

private fun logd(text: String) {
    if (CallsSensor.CONFIG.debug) Log.d(CallsSensor.TAG, text)
}

private fun logw(text: String) {
    Log.w(CallsSensor.TAG, text)
}