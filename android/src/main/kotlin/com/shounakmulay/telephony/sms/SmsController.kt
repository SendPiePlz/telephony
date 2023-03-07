package com.shounakmulay.telephony.sms

import android.Manifest
import android.Manifest.permission.READ_PHONE_STATE
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.*
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import com.shounakmulay.telephony.utils.Constants.ACTION_SMS_DELIVERED
import com.shounakmulay.telephony.utils.Constants.ACTION_SMS_SENT
import com.shounakmulay.telephony.utils.Constants.SMS_BODY
import com.shounakmulay.telephony.utils.Constants.SMS_DELIVERED_BROADCAST_REQUEST_CODE
import com.shounakmulay.telephony.utils.Constants.SMS_SENT_BROADCAST_REQUEST_CODE
import com.shounakmulay.telephony.utils.Constants.SMS_TO
import com.shounakmulay.telephony.utils.Constants.DEFAULT_CONTACT_PROJECTION
import com.shounakmulay.telephony.utils.Constants.DEFAULT_CONVERSATION_PROJECTION
import com.shounakmulay.telephony.utils.ContentUri
import java.lang.RuntimeException
import java.util.HashMap
import kotlin.collections.HashSet


class SmsController(private val context: Context) {

    // FETCH SMS
    fun getMessages(
        contentUri: ContentUri,
        projection: List<String>,
        selection: String?,
        selectionArgs: List<String>?,
        sortOrder: String?
    ): List<HashMap<String, String?>> {
        val messages = mutableListOf<HashMap<String, String?>>()

        val cursor = context.contentResolver.query(
            contentUri.uri,
            projection.toTypedArray(),
            selection,
            selectionArgs?.toTypedArray(),
            sortOrder
        )

        while (cursor != null && cursor.moveToNext()) {
            val dataObject = HashMap<String, String?>(projection.size)
            for (columnName in cursor.columnNames) {
                val columnIndex = cursor.getColumnIndex(columnName)
                if (columnIndex >= 0) {
                    dataObject[columnName] = cursor.getString(columnIndex)
                }
            }
            messages.add(dataObject)
        }
        cursor?.close()
        return messages
    }

    //
    fun getConversationFromPhone(
        selection: String,
        selectionArgs: List<String>
    ): HashMap<String, String?>? {
        val conversation = HashMap<String, String?>(3)
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS),
            selection,
            selectionArgs.toTypedArray(),
            null
        )
        if (cursor != null && cursor.moveToFirst()) {
            val threadId = cursor.getString(cursor.getColumnIndex(Telephony.Sms.THREAD_ID))
            cursor.close()
            val cursor2 = context.contentResolver.query(
                Telephony.Sms.Conversations.CONTENT_URI,
                DEFAULT_CONVERSATION_PROJECTION.toTypedArray(),
                Telephony.Sms.Conversations.THREAD_ID + "=?",
                arrayOf(threadId),
                null
            )
            if (cursor2 != null && cursor2.moveToFirst()) {
                for (columnName in cursor2.columnNames) {
                    val columnIndex = cursor2.getColumnIndex(columnName)
                    if (columnIndex >= 0) {
                        conversation[columnName] = cursor2.getString(columnIndex)
                    }
                }
                cursor2.close()
                return conversation
            }
            cursor2?.close()
        }
        cursor?.close()
        return null
    }

    // https://stackoverflow.com/a/7417523
    // Requires the app the be the default messaging app.
    fun markMessagesAsRead(
        projection: List<String>,
        selection: String,
        selectionArgs: List<String>
    ) {
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection.toTypedArray(),
            selection,
            selectionArgs.toTypedArray(),
            null
        )
        // val uri = Uri.parse("content://sms/")
        var values = ContentValues(1)
        values.put("read", true)

        while (cursor != null && cursor.moveToNext()) {
            context.getContentResolver().update(
                Telephony.Sms.Inbox.CONTENT_URI,
                values,
                "_id=?",
                arrayOf(cursor.getString(0))
            )
        }
        cursor?.close()
    }

    // https://github.com/EddieKamau/sms_advanced/blob/master/android/src/main/kotlin/com/elyudde/sms_advanced/ContactQuery.kt
    // https://developer.android.com/reference/kotlin/android/provider/ContactsContract.PhoneLookup
    // https://developer.android.com/reference/kotlin/android/provider/ContactsContract.Data
    fun getContacts(includeThumbnail: Boolean): List<HashMap<String, Any?>> {
        val mapSize = if (includeThumbnail) 3 else 2
        val contacts = mutableListOf<HashMap<String, Any?>>()
        val selection = ContactsContract.Contacts.HAS_PHONE_NUMBER + ">0"
        val sortOrder = ContactsContract.Contacts.DISPLAY_NAME + " ASC"
        val cursor = context.contentResolver.query(ContactsContract.Data.CONTENT_URI, DEFAULT_CONTACT_PROJECTION, selection, null, sortOrder)
        val past = HashSet<String>(cursor?.getCount() ?: 0)

        // Retrieve all contacts
        while (cursor != null && cursor.moveToNext()) {
            val contact = HashMap<String, Any?>(mapSize)
            val displayName = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME))
            // Filter out duplicates
            if (!past.contains(displayName)) {
                contact["displayName"] = displayName
                contact["phone"] = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))

                // retrieve thumbnail
                if (includeThumbnail) {
                    val contactId = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts._ID))
                    contact["thumbnail"] = getContactThumbnail(contactId)
                }
                past.add(displayName)
                contacts.add(contact)
            }
        }
        cursor?.close()
        return contacts
    }

    // https://github.com/lukasgit/flutter_contacts/blob/master/android/src/main/java/flutter/plugins/contactsservice/contactsservice/ContactsServicePlugin.java#L525
    fun getContactFromPhone(phone: String, includeThumbnail: Boolean): HashMap<String, Any?>? {
        if (phone.length < 5) return null
        val mapSize = if (includeThumbnail) 3 else 2
        val contact = HashMap<String, Any?>(mapSize)
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone))
        val cursor = context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.CONTACT_ID), null, null, null)

        // retrieve contact ID associated with the phone number
        if (cursor != null && cursor.moveToFirst()) {
            val contactId = cursor.getString(cursor.getColumnIndex(ContactsContract.PhoneLookup.CONTACT_ID)).toString()
            val projection = arrayOf(ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
            var selection = ContactsContract.Data.CONTACT_ID + " = " + contactId
            val cursor2 = context.contentResolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, null, null)

            // retrieve contact details
            if (cursor2 != null && cursor2.moveToFirst()) {
                contact["displayName"] = cursor2.getString(cursor2.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME))
                contact["phone"] = cursor2.getString(cursor2.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
                
                // retrieve thumbnail
                if (includeThumbnail) {
                    contact["thumbnail"] = getContactThumbnail(contactId)
                }
            }
            cursor2?.close()
        }
        cursor?.close()

        if (contact.isEmpty()) return null
        else return contact
    }

    // https://developer.android.com/reference/kotlin/android/provider/ContactsContract.Contacts.Photo
    private fun getContactThumbnail(contactId: String): ByteArray? {
        var blob: ByteArray? = null
        val contactUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId)
        val uri = Uri.withAppendedPath(contactUri, ContactsContract.Contacts.Photo.CONTENT_DIRECTORY)
        val projection = arrayOf(ContactsContract.Contacts.Photo.PHOTO)
        val cursor = context.contentResolver.query(uri, projection, null, null,null)
        
        if (cursor != null && cursor.moveToFirst()) {
            blob = cursor.getBlob(0)
        }
        cursor?.close()
        return blob
    }

    // SEND SMS
    fun sendSms(destinationAddress: String, messageBody: String, listenStatus: Boolean) {
        val smsManager = getSmsManager()
        if (listenStatus) {
            val pendingIntents = getPendingIntents()
            smsManager.sendTextMessage(
                destinationAddress,
                null,
                messageBody,
                pendingIntents.first,
                pendingIntents.second
            )
        } else {
            smsManager.sendTextMessage(destinationAddress, null, messageBody, null, null)
        }
    }

    fun sendMultipartSms(destinationAddress: String, messageBody: String, listenStatus: Boolean) {
        val smsManager = getSmsManager()
        val messageParts = smsManager.divideMessage(messageBody)
        if (listenStatus) {
            val pendingIntents = getMultiplePendingIntents(messageParts.size)
            smsManager.sendMultipartTextMessage(
                destinationAddress,
                null,
                messageParts,
                pendingIntents.first,
                pendingIntents.second
            )
        } else {
            smsManager.sendMultipartTextMessage(destinationAddress, null, messageParts, null, null)
        }
    }

    private fun getMultiplePendingIntents(size: Int): Pair<ArrayList<PendingIntent>, ArrayList<PendingIntent>> {
        val sentPendingIntents = arrayListOf<PendingIntent>()
        val deliveredPendingIntents = arrayListOf<PendingIntent>()
        for (i in 1..size) {
            val pendingIntents = getPendingIntents()
            sentPendingIntents.add(pendingIntents.first)
            deliveredPendingIntents.add(pendingIntents.second)
        }
        return Pair(sentPendingIntents, deliveredPendingIntents)
    }

    fun sendSmsIntent(destinationAddress: String, messageBody: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse(SMS_TO + destinationAddress)
            putExtra(SMS_BODY, messageBody)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.applicationContext.startActivity(intent)
    }

    private fun getPendingIntents(): Pair<PendingIntent, PendingIntent> {
        val sentIntent = Intent(ACTION_SMS_SENT).apply {
            `package` = context.applicationContext.packageName
            flags = Intent.FLAG_RECEIVER_REGISTERED_ONLY
        }
        val sentPendingIntent = PendingIntent.getBroadcast(
            context,
            SMS_SENT_BROADCAST_REQUEST_CODE,
            sentIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val deliveredIntent = Intent(ACTION_SMS_DELIVERED).apply {
            `package` = context.applicationContext.packageName
            flags = Intent.FLAG_RECEIVER_REGISTERED_ONLY
        }
        val deliveredPendingIntent = PendingIntent.getBroadcast(
            context,
            SMS_DELIVERED_BROADCAST_REQUEST_CODE,
            deliveredIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        return Pair(sentPendingIntent, deliveredPendingIntent)
    }

    private fun getSmsManager(): SmsManager {
        val subscriptionId = SmsManager.getDefaultSmsSubscriptionId()
        if (subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val smsManager = getSystemService(context, SmsManager::class.java) ?: throw RuntimeException("Flutter Telephony: Error getting SmsManager")
                smsManager.createForSubscriptionId(subscriptionId)
            } else {
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            }
        }
        else {
            return SmsManager.getDefault()
        }
        // return smsManager
    }

    // PHONE
    fun openDialer(phoneNumber: String) {
        val dialerIntent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$phoneNumber")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        context.startActivity(dialerIntent)
    }

    @RequiresPermission(allOf = [Manifest.permission.CALL_PHONE])
    fun dialPhoneNumber(phoneNumber: String) {
        val callIntent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        if (callIntent.resolveActivity(context.packageManager) != null) {
            context.applicationContext.startActivity(callIntent)
        }
    }

    // STATUS
    fun isSmsCapable(): Boolean {
        val telephonyManager = getTelephonyManager()
        return telephonyManager.isSmsCapable
    }

    fun getCellularDataState(): Int {
        return getTelephonyManager().dataState
    }

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    fun getCallState(): Int {
        val telephonyManager = getTelephonyManager()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyManager.callStateForSubscription
        } else {
            telephonyManager.callState
        }
    }

    fun getDataActivity(): Int {
        return getTelephonyManager().dataActivity
    }

    fun getNetworkOperator(): String {
        return getTelephonyManager().networkOperator
    }

    fun getNetworkOperatorName(): String {
        return getTelephonyManager().networkOperatorName
    }

    @SuppressLint("MissingPermission")
    fun getDataNetworkType(): Int {
        val telephonyManager = getTelephonyManager()
        return if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            telephonyManager.dataNetworkType
        } else {
            telephonyManager.networkType
        }
    }

    fun getPhoneType(): Int {
        return getTelephonyManager().phoneType
    }

    fun getSimOperator(): String {
        return getTelephonyManager().simOperator
    }

    fun getSimOperatorName(): String {
        return getTelephonyManager().simOperatorName
    }

    fun getSimState(): Int {
        return getTelephonyManager().simState
    }

    fun isNetworkRoaming(): Boolean {
        return getTelephonyManager().isNetworkRoaming
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.READ_PHONE_STATE])
    fun getServiceState(): Int? {
        val serviceState = getTelephonyManager().serviceState
        return serviceState?.state
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun getSignalStrength(): List<Int>? {
        val signalStrength = getTelephonyManager().signalStrength
        return signalStrength?.cellSignalStrengths?.map {
            return@map it.level
        }
    }

    private fun getTelephonyManager(): TelephonyManager {
        val subscriptionId = SmsManager.getDefaultSmsSubscriptionId()
        val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            telephonyManager.createForSubscriptionId(subscriptionId)
        } else {
            telephonyManager
        }
    }
}