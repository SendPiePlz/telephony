package com.shounakmulay.telephony.sms

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.shounakmulay.telephony.PermissionsController
import com.shounakmulay.telephony.utils.ActionType
import com.shounakmulay.telephony.utils.Constants
import com.shounakmulay.telephony.utils.Constants.ADDRESS
import com.shounakmulay.telephony.utils.Constants.AMOUNT
import com.shounakmulay.telephony.utils.Constants.BACKGROUND_HANDLE
import com.shounakmulay.telephony.utils.Constants.CALL_REQUEST_CODE
import com.shounakmulay.telephony.utils.Constants.DEFAULT_SMS_PROJECTION
import com.shounakmulay.telephony.utils.Constants.SPECIAL_SMS_PROJECTION
import com.shounakmulay.telephony.utils.Constants.DEFAULT_CONVERSATION_PROJECTION
import com.shounakmulay.telephony.utils.Constants.CONTACT_QUERY_REQUEST_CODE
import com.shounakmulay.telephony.utils.Constants.FAILED_FETCH
import com.shounakmulay.telephony.utils.Constants.GET_STATUS_REQUEST_CODE
import com.shounakmulay.telephony.utils.Constants.ILLEGAL_ARGUMENT
import com.shounakmulay.telephony.utils.Constants.LISTEN_STATUS
import com.shounakmulay.telephony.utils.Constants.MESSAGE_BODY
import com.shounakmulay.telephony.utils.Constants.PERMISSION_DENIED
import com.shounakmulay.telephony.utils.Constants.PERMISSION_DENIED_MESSAGE
import com.shounakmulay.telephony.utils.Constants.PERMISSION_REQUEST_CODE
import com.shounakmulay.telephony.utils.Constants.PHONE
import com.shounakmulay.telephony.utils.Constants.INCLUDE_THUMBNAIL
import com.shounakmulay.telephony.utils.Constants.PHONE_NUMBER
import com.shounakmulay.telephony.utils.Constants.PROJECTION
import com.shounakmulay.telephony.utils.Constants.SELECTION
import com.shounakmulay.telephony.utils.Constants.SELECTION_ARGS
import com.shounakmulay.telephony.utils.Constants.SETUP_HANDLE
import com.shounakmulay.telephony.utils.Constants.SHARED_PREFERENCES_NAME
import com.shounakmulay.telephony.utils.Constants.SHARED_PREFS_DISABLE_BACKGROUND_EXE
import com.shounakmulay.telephony.utils.Constants.SMS_BACKGROUND_REQUEST_CODE
import com.shounakmulay.telephony.utils.Constants.SMS_DELIVERED
import com.shounakmulay.telephony.utils.Constants.SMS_QUERY_REQUEST_CODE
import com.shounakmulay.telephony.utils.Constants.SMS_SEND_REQUEST_CODE
import com.shounakmulay.telephony.utils.Constants.SMS_SENT
import com.shounakmulay.telephony.utils.Constants.SORT_ORDER
import com.shounakmulay.telephony.utils.Constants.WRONG_METHOD_TYPE
import com.shounakmulay.telephony.utils.ContentUri
import com.shounakmulay.telephony.utils.SmsAction
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import java.lang.IllegalArgumentException


class SmsMethodCallHandler(
    private val context: Context,
    private val smsController: SmsController,
    private val permissionsController: PermissionsController
) : PluginRegistry.RequestPermissionsResultListener,
    MethodChannel.MethodCallHandler,
    BroadcastReceiver() {

  private lateinit var result: MethodChannel.Result
  private lateinit var action: SmsAction
  private lateinit var foregroundChannel: MethodChannel
  private lateinit var activity: Activity

  private var projection: List<String>? = null
  private var selection: String? = null
  private var selectionArgs: List<String>? = null
  private var sortOrder: String? = null
  private var amount: Int = 0

  private var includeThumbnail: Boolean? = false
  private var phone: String? = null

  private lateinit var messageBody: String
  private lateinit var address: String
  private var listenStatus: Boolean = false

  private var setupHandle: Long = -1
  private var backgroundHandle: Long = -1

  private lateinit var phoneNumber: String

  private var requestCode: Int = -1

  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    this.result = result

    action = SmsAction.fromMethod(call.method)

    if (action == SmsAction.NO_SUCH_METHOD) {
      result.notImplemented()
      return
    }

    when (action.toActionType()) {
      ActionType.GET_SMS -> {
        projection = call.argument(PROJECTION)
        selection = call.argument(SELECTION)
        selectionArgs = call.argument(SELECTION_ARGS)
        sortOrder = call.argument(SORT_ORDER)
        amount = call.argument(AMOUNT) ?: 0

        handleMethod(action, SMS_QUERY_REQUEST_CODE)
      }
      ActionType.MARK_SMS -> {
        selection = call.argument(SELECTION)
        selectionArgs = call.argument(SELECTION_ARGS)

        handleMethod(action, SMS_QUERY_REQUEST_CODE)
      }
      ActionType.GET_CONTACTS -> {
        includeThumbnail = call.argument(INCLUDE_THUMBNAIL)
        phone = call.argument(PHONE)

        handleMethod(action, CONTACT_QUERY_REQUEST_CODE)
      }
      ActionType.SEND_SMS -> {
        if (call.hasArgument(MESSAGE_BODY)
            && call.hasArgument(ADDRESS)) {
          val messageBody = call.argument<String>(MESSAGE_BODY)
          val address = call.argument<String>(ADDRESS)
          if (messageBody.isNullOrBlank() || address.isNullOrBlank()) {
            result.error(ILLEGAL_ARGUMENT, Constants.MESSAGE_OR_ADDRESS_CANNOT_BE_NULL, null)
            return
          }

          this.messageBody = messageBody
          this.address = address

          listenStatus = call.argument(LISTEN_STATUS) ?: false
        }
        handleMethod(action, SMS_SEND_REQUEST_CODE)
      }
      ActionType.BACKGROUND -> {
        if (call.hasArgument(SETUP_HANDLE)
            && call.hasArgument(BACKGROUND_HANDLE)) {
          val setupHandle = call.argument<Long>(SETUP_HANDLE)
          val backgroundHandle = call.argument<Long>(BACKGROUND_HANDLE)
          if (setupHandle == null || backgroundHandle == null) {
            result.error(ILLEGAL_ARGUMENT, "Setup handle or background handle missing", null)
            return
          }

          this.setupHandle = setupHandle
          this.backgroundHandle = backgroundHandle
        }
        handleMethod(action, SMS_BACKGROUND_REQUEST_CODE)
      }
      ActionType.GET -> handleMethod(action, GET_STATUS_REQUEST_CODE)
      ActionType.PERMISSION -> handleMethod(action, PERMISSION_REQUEST_CODE)
      ActionType.CALL -> {
        if (call.hasArgument(PHONE_NUMBER)) {
          val phoneNumber = call.argument<String>(PHONE_NUMBER)

          if (!phoneNumber.isNullOrBlank()) {
            this.phoneNumber = phoneNumber
          }

          handleMethod(action, CALL_REQUEST_CODE)
        }
      }
    }
  }

  /**
   * Called by [handleMethod] after checking the permissions.
   *
   * #####
   *
   * If permission was not previously granted, [handleMethod] will request the user for permission
   *
   * Once user grants the permission this method will be executed.
   *
   * #####
   */
  private fun execute(smsAction: SmsAction) {
    try {
      when (smsAction.toActionType()) {
        ActionType.GET_SMS -> handleGetSmsActions(smsAction)
        ActionType.SEND_SMS -> handleSendSmsActions(smsAction)
        ActionType.MARK_SMS -> handleMarkSmsActions(smsAction)
        ActionType.GET_CONTACTS -> handleGetContactsActions(smsAction)
        ActionType.BACKGROUND -> handleBackgroundActions(smsAction)
        ActionType.GET -> handleGetActions(smsAction)
        ActionType.PERMISSION -> result.success(true)
        ActionType.CALL -> handleCallActions(smsAction)
      }
    } catch (e: IllegalArgumentException) {
      result.error(ILLEGAL_ARGUMENT, WRONG_METHOD_TYPE, null)
    } catch (e: RuntimeException) {
      result.error(FAILED_FETCH, e.message, null)
    }
  }

  private fun handleGetSmsActions(smsAction: SmsAction) {
    if (projection == null) {
      projection = if (smsAction == SmsAction.GET_CONVERSATIONS) DEFAULT_CONVERSATION_PROJECTION else DEFAULT_SMS_PROJECTION
    }
    val contentUri = when (smsAction) {
      SmsAction.GET_ALL_SMS -> ContentUri.ALL_SMS
      SmsAction.GET_INBOX -> ContentUri.INBOX
      SmsAction.GET_SENT -> ContentUri.SENT
      SmsAction.GET_DRAFT -> ContentUri.DRAFT
      SmsAction.GET_CONVERSATIONS,
      SmsAction.GET_CONVERSATION_FROM_PHONE -> ContentUri.CONVERSATIONS
      else -> throw IllegalArgumentException()
    }
    if (smsAction == SmsAction.GET_CONVERSATION_FROM_PHONE) {
      val conversation = smsController.getConversationFromPhone(selection!!, selectionArgs!!)
      result.success(conversation)
    }
    else if (amount > 0) {
      val messages = smsController.getSomeMessages(contentUri, projection!!, selection, selectionArgs, sortOrder, amount)
      result.success(messages)
    }
    else {
      val messages = smsController.getMessages(contentUri, projection!!, selection, selectionArgs, sortOrder)
      result.success(messages)
    }
  }

  private fun handleSendSmsActions(smsAction: SmsAction) {
    if (listenStatus) {
      val intentFilter = IntentFilter().apply {
        addAction(Constants.ACTION_SMS_SENT)
        addAction(Constants.ACTION_SMS_DELIVERED)
      }
      context.applicationContext.registerReceiver(this, intentFilter)
    }
    when (smsAction) {
      SmsAction.SEND_SMS -> smsController.sendSms(address, messageBody, listenStatus)
      SmsAction.SEND_MULTIPART_SMS -> smsController.sendMultipartSms(address, messageBody, listenStatus)
      SmsAction.SEND_SMS_INTENT -> smsController.sendSmsIntent(address, messageBody)
      else -> throw IllegalArgumentException()
    }
    result.success(null)
  }

  private fun handleMarkSmsActions(smsAction: SmsAction) {
    if (selection == null || selectionArgs == null) {
      throw IllegalArgumentException()
    }
    when (smsAction) {
      SmsAction.MARK_SMS_AS_READ -> {
        smsController.markMessagesAsRead(SPECIAL_SMS_PROJECTION, selection!!, selectionArgs!!)
        // return
      }
      else -> throw IllegalArgumentException()
    }
    result.success(null)
  }

  private fun handleGetContactsActions(smsAction: SmsAction) {
    when (smsAction) {
      SmsAction.GET_CONTACTS -> {
        result.success(smsController.getContacts(includeThumbnail!!))
        return
      }
      SmsAction.GET_CONTACT_FROM_PHONE -> {
        result.success(smsController.getContactFromPhone(phone!!, includeThumbnail!!))
        return
      }
      else -> throw IllegalArgumentException()
    }
    result.success(null)
  }

  private fun handleBackgroundActions(smsAction: SmsAction) {
    when (smsAction) {
      SmsAction.START_BACKGROUND_SERVICE -> {
        val preferences = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().putBoolean(SHARED_PREFS_DISABLE_BACKGROUND_EXE, false).apply()
        IncomingSmsHandler.setBackgroundSetupHandle(context, setupHandle)
        IncomingSmsHandler.setBackgroundMessageHandle(context, backgroundHandle)
      }
      SmsAction.BACKGROUND_SERVICE_INITIALIZED -> {
        IncomingSmsHandler.onChannelInitialized(context.applicationContext)
      }
      SmsAction.DISABLE_BACKGROUND_SERVICE -> {
        val preferences = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().putBoolean(SHARED_PREFS_DISABLE_BACKGROUND_EXE, true).apply()
      }
      else -> throw IllegalArgumentException()
    }
  }

  @SuppressLint("MissingPermission")
  private fun handleGetActions(smsAction: SmsAction) {
    smsController.apply {
      val value: Any = when (smsAction) {
        SmsAction.IS_SMS_CAPABLE -> isSmsCapable()
        SmsAction.GET_CELLULAR_DATA_STATE -> getCellularDataState()
        SmsAction.GET_CALL_STATE -> getCallState()
        SmsAction.GET_DATA_ACTIVITY -> getDataActivity()
        SmsAction.GET_NETWORK_OPERATOR -> getNetworkOperator()
        SmsAction.GET_NETWORK_OPERATOR_NAME -> getNetworkOperatorName()
        SmsAction.GET_DATA_NETWORK_TYPE -> getDataNetworkType()
        SmsAction.GET_PHONE_TYPE -> getPhoneType()
        SmsAction.GET_SIM_OPERATOR -> getSimOperator()
        SmsAction.GET_SIM_OPERATOR_NAME -> getSimOperatorName()
        SmsAction.GET_SIM_STATE -> getSimState()
        SmsAction.IS_NETWORK_ROAMING -> isNetworkRoaming()
        SmsAction.GET_SIGNAL_STRENGTH -> {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSignalStrength()
                ?: result.error("SERVICE_STATE_NULL", "Error getting service state", null)

          } else {
            result.error("INCORRECT_SDK_VERSION", "getServiceState() can only be called on Android Q and above", null)
          }
        }
        SmsAction.GET_SERVICE_STATE -> {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getServiceState()
                ?: result.error("SERVICE_STATE_NULL", "Error getting service state", null)
          } else {
            result.error("INCORRECT_SDK_VERSION", "getServiceState() can only be called on Android O and above", null)
          }
        }
        else -> throw IllegalArgumentException()
      }
      result.success(value)
    }
  }

  @SuppressLint("MissingPermission")
  private fun handleCallActions(smsAction: SmsAction) {
    when (smsAction) {
      SmsAction.OPEN_DIALER -> smsController.openDialer(phoneNumber)
      SmsAction.DIAL_PHONE_NUMBER -> smsController.dialPhoneNumber(phoneNumber)
      else -> throw IllegalArgumentException()
    }
  }


  /**
   * Calls the [execute] method after checking if the necessary permissions are granted.
   *
   * If not granted then it will request the permission from the user.
   */
  private fun handleMethod(smsAction: SmsAction, requestCode: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || checkOrRequestPermission(smsAction, requestCode)) {
      execute(smsAction)
    }
  }

  /**
   * Check and request if necessary for all the SMS permissions listed in the manifest
   */
  @RequiresApi(Build.VERSION_CODES.M)
  fun checkOrRequestPermission(smsAction: SmsAction, requestCode: Int): Boolean {
    this.action = smsAction
    this.requestCode = requestCode
    when (smsAction) {
      SmsAction.GET_ALL_SMS,
      SmsAction.GET_INBOX,
      SmsAction.GET_SENT,
      SmsAction.GET_DRAFT,
      SmsAction.MARK_SMS_AS_READ,
      SmsAction.GET_CONVERSATIONS,
      SmsAction.GET_CONVERSATION_FROM_PHONE,
      SmsAction.SEND_SMS,
      SmsAction.SEND_MULTIPART_SMS,
      SmsAction.SEND_SMS_INTENT,
      SmsAction.START_BACKGROUND_SERVICE,
      SmsAction.BACKGROUND_SERVICE_INITIALIZED,
      SmsAction.DISABLE_BACKGROUND_SERVICE,
      SmsAction.REQUEST_SMS_PERMISSIONS -> {
        val permissions = permissionsController.getSmsPermissions()
        return _checkOrRequestPermission(permissions, requestCode)
      }
      SmsAction.GET_CONTACTS,
      SmsAction.GET_CONTACT_FROM_PHONE -> {
        val permissions = permissionsController.getContactsPermissions()
        return _checkOrRequestPermission(permissions, requestCode)
      }
      SmsAction.GET_DATA_NETWORK_TYPE,
      SmsAction.OPEN_DIALER,
      SmsAction.DIAL_PHONE_NUMBER,
      SmsAction.REQUEST_PHONE_PERMISSIONS -> {
        val permissions = permissionsController.getPhonePermissions()
        return _checkOrRequestPermission(permissions, requestCode)
      }
      SmsAction.GET_SERVICE_STATE -> {
        val permissions = permissionsController.getServiceStatePermissions()
        return _checkOrRequestPermission(permissions, requestCode)
      }
      SmsAction.REQUEST_PHONE_AND_SMS_PERMISSIONS -> {
        val permissions = listOf(permissionsController.getSmsPermissions(), permissionsController.getPhonePermissions()).flatten()
        return _checkOrRequestPermission(permissions, requestCode)
      }
      SmsAction.IS_SMS_CAPABLE,
      SmsAction.GET_CELLULAR_DATA_STATE,
      SmsAction.GET_CALL_STATE,
      SmsAction.GET_DATA_ACTIVITY,
      SmsAction.GET_NETWORK_OPERATOR,
      SmsAction.GET_NETWORK_OPERATOR_NAME,
      SmsAction.GET_PHONE_TYPE,
      SmsAction.GET_SIM_OPERATOR,
      SmsAction.GET_SIM_OPERATOR_NAME,
      SmsAction.GET_SIM_STATE,
      SmsAction.IS_NETWORK_ROAMING,
      SmsAction.GET_SIGNAL_STRENGTH,
      SmsAction.NO_SUCH_METHOD -> return true
    }
  }

  fun setActivity(activity: Activity) {
    this.activity = activity
  }

  @RequiresApi(Build.VERSION_CODES.M)
  private fun _checkOrRequestPermission(permissions: List<String>, requestCode: Int): Boolean {
    permissionsController.apply {
      
      if (!::activity.isInitialized) {
        return hasRequiredPermissions(permissions)
      }
      
      if (!hasRequiredPermissions(permissions)) {
        // requestPermissions(activity, permissions, requestCode)
        return false
      }
      return true
    }
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {

    permissionsController.isRequestingPermission = false

    val deniedPermissions = mutableListOf<String>()
    if (requestCode != this.requestCode && !this::action.isInitialized) {
      return false
    }

    val allPermissionGranted = grantResults.foldIndexed(true) { i, acc, result ->
      if (result == PackageManager.PERMISSION_DENIED) {
        permissions.let { deniedPermissions.add(it[i]) }
      }
      return@foldIndexed acc && result == PackageManager.PERMISSION_GRANTED
    }

    return if (allPermissionGranted) {
      execute(action)
      true
    } else {
      onPermissionDenied(deniedPermissions)
      false
    }
  }

  private fun onPermissionDenied(deniedPermissions: List<String>) {
    result.error(PERMISSION_DENIED, PERMISSION_DENIED_MESSAGE, deniedPermissions)
  }

  fun setForegroundChannel(channel: MethodChannel) {
    foregroundChannel = channel
  }

  override fun onReceive(ctx: Context?, intent: Intent?) {
    if (intent != null) {
      when (intent.action) {
        Constants.ACTION_SMS_SENT -> foregroundChannel.invokeMethod(SMS_SENT, null)
        Constants.ACTION_SMS_DELIVERED -> {
          foregroundChannel.invokeMethod(SMS_DELIVERED, null)
          context.unregisterReceiver(this)
        }
      }
    }
  }
}
