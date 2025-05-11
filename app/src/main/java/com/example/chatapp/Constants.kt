package com.example.chatapp.utils

object Constants {

    // region Intent Keys
    const val CHAT_ID = "chat_id"
    const val USER_ID = "user_id"
    const val CHAT_NAME = "chat_name"
    const val MESSAGE_ID = "message_id"
    const val REQUEST_ADD_USERS = 1001
    const val FCM_SERVER_KEY = "0a0c30df0009e15f52351905816841b7f599cdfc"



    // endregion



    // region Firebase Database
    object Paths {
        const val USERS = "users"
        const val CHATS = "chats"
        const val MESSAGES = "messages"
        const val PARTICIPANTS = "participants"
        const val LAST_MESSAGE = "lastMessage"
    }

    object MessageTypes {
        const val TEXT = "text"
        const val IMAGE = "image"
        const val SYSTEM = "system"
    }
    // endregion

    // region Permissions
    object RequestCodes {
        const val IMAGE_PICK = 100
        const val PERMISSION_REQUEST = 101
        const val CAMERA_REQUEST = 102
        const val GALLERY_REQUEST = 103
    }

    object Permissions {
        const val READ_STORAGE = android.Manifest.permission.READ_EXTERNAL_STORAGE
        const val WRITE_STORAGE = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        const val CAMERA = android.Manifest.permission.CAMERA
    }
    // endregion

    // region Default Values
    object Defaults {
        const val UNKNOWN_USER_NAME = "Unknown User"
        const val SYSTEM_USER_ID = "system"
        const val EMPTY_STRING = ""
        const val DEFAULT_CHAT_NAME = "New Chat"
    }
    // endregion

    // region Error Messages
    object Errors {
        const val NETWORK_ERROR = "Network error occurred"
        const val PERMISSION_DENIED = "Permission denied"
        const val INVALID_DATA = "Invalid data format"
    }
    // endregion
}