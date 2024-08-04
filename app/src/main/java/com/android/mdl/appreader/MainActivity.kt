package com.android.mdl.appreader

import android.os.Bundle
import android.os.ResultReceiver
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.identitycredentials.CredentialOption
import com.google.android.gms.identitycredentials.GetCredentialRequest
import com.google.android.gms.identitycredentials.IdentityCredentialClient
import com.google.android.gms.identitycredentials.IdentityCredentialManager
import com.google.android.gms.identitycredentials.PendingGetCredentialHandle
import com.google.android.gms.identitycredentials.RegistrationRequest
import com.google.android.gms.identitycredentials.RegistrationResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.android.mdl.appreader.R

class MainActivity : AppCompatActivity() {
    private lateinit var credentialClient: IdentityCredentialClient
    private val REQUEST_CODE_GET_CREDENTIAL = 777
    private val serverDomain = "fido-kokukuma.jp.ngrok.io" // サーバードメインを設定してください

    companion object {
        private const val PLAY_SERVICES_RESOLUTION_REQUEST = 9000
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val googleApiAvailability = GoogleApiAvailability.getInstance()
        val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(this)
        if (resultCode != ConnectionResult.SUCCESS) {
            if (googleApiAvailability.isUserResolvableError(resultCode)) {
                googleApiAvailability.getErrorDialog(this, resultCode, PLAY_SERVICES_RESOLUTION_REQUEST)?.show()
            } else {
                Log.e("MainActivity", "This device is not supported")
                finish()
            }
        } else {
            initializeCredentialClient()
        }
    }
    private fun initializeCredentialClient() {
        // IdentityCredentialClient を初期化
        credentialClient = IdentityCredentialManager.getClient(this)

        if (::credentialClient.isInitialized) {
            Log.d("MainActivity", "IdentityCredentialClient initialized successfully")
        } else {
            Log.e("MainActivity", "Failed to initialize IdentityCredentialClient")
        }

        // クレデンシャル取得ボタンのクリックリスナー
        findViewById<Button>(R.id.btnGetCredential).setOnClickListener {
            lifecycleScope.launch{
                getCredential()
            }
        }

        // クレデンシャル登録ボタンのクリックリスナー
        findViewById<Button>(R.id.btnRegisterCredential).setOnClickListener {
            registerCredential()
        }
    }

    private fun registerCredential() {
        /*
        val request = RegistrationRequest(
            credentials = byteArrayOf(1, 2, 3, 4), // ダミーデータ。実際のアプリでは適切なデータを使用してください
            matcher = byteArrayOf(5, 6, 7, 8), // ダミーデータ
            type = "credential_type"
        )
        */
        val request = RegistrationRequest(
            credentials = byteArrayOf(1, 2, 3, 4), // ダミーデータ
            matcher = byteArrayOf(5, 6, 7, 8), // ダミーデータ
            type = "com.credman.IdentityCredential",
            requestType = "storage", // または適切な文字列値
            protocolTypes = listOf("protocol_type") // 適切なプロトコルタイプのリスト
        )


        credentialClient.registerCredentials(request)
            .addOnSuccessListener { response: RegistrationResponse ->
                Log.d("MainActivity", "Credential registered successfully")
            }
            .addOnFailureListener { e ->
                Log.e("MainActivity", "Error registering credential", e)
            }
    }

    private suspend fun getCredential() {
        var credentialClient = IdentityCredentialManager.getClient(this)

        val identityRequestData = getIdentityRequestData(serverDomain)

        val requestMatcher = JSONObject().apply {
            put("providers", JSONArray().put(
                JSONObject().apply {
                    put("protocol", "preview")
                    put("request", identityRequestData)
                }
            ))
        }

        val credentialOption = CredentialOption(
            type = "com.credman.IdentityCredential",
            credentialRetrievalData = Bundle(),
            candidateQueryData = Bundle(),
            requestMatcher = requestMatcher.toString(),
            requestType = "",
            protocolType = ""
        )

        val request = GetCredentialRequest(
            credentialOptions = listOf(credentialOption),
            data = Bundle(),
            origin = null,  // オプションのパラメータ
            resultReceiver = object: ResultReceiver(null) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    Log.e("MainActivity", "Error getting credential: $resultCode $resultData")
                }
            } as ResultReceiver
        )

        credentialClient.getCredential(request)
            .addOnSuccessListener { handle: PendingGetCredentialHandle ->
                // PendingIntent を使用してクレデンシャル取得プロセスを開始
                handle.pendingIntent.let { pendingIntent ->
                    startIntentSenderForResult(
                        pendingIntent.intentSender,
                        REQUEST_CODE_GET_CREDENTIAL,
                        null, 0, 0, 0, null
                    )
                }
            }
            .addOnFailureListener { e ->
                when (e) {
                    is ApiException -> {
                        when (e.statusCode) {
                            CommonStatusCodes.API_NOT_CONNECTED -> {
                                Log.e("MainActivity", "Google Play Services API not connected. Status code: ${e.statusCode}", e)
                                // Google Play Services の接続を試みる
                                val googleApiAvailability = GoogleApiAvailability.getInstance()
                                val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(this)
                                if (resultCode != ConnectionResult.SUCCESS) {
                                    if (googleApiAvailability.isUserResolvableError(resultCode)) {
                                        googleApiAvailability.getErrorDialog(this, resultCode, 1000)?.show()
                                    } else {
                                        Log.e("MainActivity", "This device is not supported")
                                        // ユーザーに通知
                                    }
                                }
                                Log.d("MainActivity", "Looks success: $resultCode")
                            }
                            else -> Log.e("MainActivity", "ApiException with status code: ${e.statusCode}", e)
                        }
                    }
                    else -> Log.e("MainActivity", "Error getting credential", e)
                }
            }
    }
}


suspend fun getIdentityRequestData(serverDomain: String): String {
    return withContext(Dispatchers.IO) {
        val client = OkHttpClient()

        val jsonBody = JSONObject().put("protocol", "preview").toString()
        val body = jsonBody.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://$serverDomain/getIdentityRequest")
            .post(body)
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) throw Exception("Request failed: ${response.code}")

        val responseBody = response.body?.string() ?: throw Exception("Empty response body")
        val jsonResponse = JSONObject(responseBody)

        jsonResponse.getString("data")
    }
}
