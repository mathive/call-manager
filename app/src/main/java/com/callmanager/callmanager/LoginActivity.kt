package com.callmanager.callmanager

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.callmanager.callmanager.databinding.ActivityLoginBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var googleSignInClient: GoogleSignInClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // Configure Google Sign In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id)) 
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        binding.btnGoogleSignIn.setOnClickListener {
            handleSignInClick()
        }

        // Auto request permissions on first launch
        val initialUngranted = getUngrantedPermissions()
        if (initialUngranted.isNotEmpty()) {
            requestPermissionLauncher.launch(initialUngranted.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        FirestoreUi.showPendingMessageIfAny(this)
        if (auth.currentUser != null) {
            navigateToMain()
        }
    }

    override fun onStart() {
        super.onStart()
        if (auth.currentUser != null) {
            navigateToMain()
        }
    }

    private fun handleSignInClick() {
        when {
            getUngrantedPermissions().isNotEmpty() -> {
                val ungrantedPermissions = getUngrantedPermissions()
                val shouldShowRationale = ungrantedPermissions.any {
                    ActivityCompat.shouldShowRequestPermissionRationale(this, it)
                }

                if (shouldShowRationale) {
                    showPermissionDialog()
                } else {
                    requestPermissionLauncher.launch(ungrantedPermissions.toTypedArray())
                }
            }
            !Settings.canDrawOverlays(this) -> {
                showOverlayDialog()
            }
            else -> {
                signInWithGoogle()
            }
        }
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_required_title)
            .setMessage(R.string.permission_required_message)
            .setPositiveButton(R.string.enable) { _, _ ->
                requestPermissionLauncher.launch(getUngrantedPermissions().toTypedArray())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showOverlayDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_permission_title)
            .setMessage(R.string.overlay_permission_message)
            .setPositiveButton(R.string.enable) { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_denied_permanent_title)
            .setMessage(R.string.permission_denied_permanent_message)
            .setPositiveButton(R.string.settings_label) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun getUngrantedPermissions(): List<String> {
        return getRuntimePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getRuntimePermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions += Manifest.permission.READ_PHONE_NUMBERS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.permissions_granted, Toast.LENGTH_SHORT).show()
            } else {
                showOverlayDialog()
            }
        } else {
            // If user denied with "Don't ask again", show settings dialog
            val stillUngranted = getUngrantedPermissions()
            val deniedPermanently = stillUngranted.any { 
                !ActivityCompat.shouldShowRequestPermissionRationale(this, it) 
            }
            
            if (deniedPermanently) {
                showSettingsDialog()
            } else {
                Toast.makeText(this, R.string.permissions_required_all, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun signInWithGoogle() {
        val signInIntent = googleSignInClient.signInIntent
        googleLauncher.launch(signInIntent)
    }

    private val googleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)!!
                firebaseAuthWithGoogle(account.idToken!!)
            } catch (e: ApiException) {
                Toast.makeText(this, getString(R.string.google_sign_in_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    user?.let { firebaseUser ->
                        // Check if user already exists in Firestore
                        db.collection("users").document(firebaseUser.uid).get()
                            .addOnSuccessListener { document ->
                                if (!document.exists()) {
                                    // Create new user profile with default role "Guest"
                                    val userData = hashMapOf(
                                        "name" to firebaseUser.displayName,
                                        "email" to firebaseUser.email,
                                        "isVerified" to true,
                                        "role" to "guest"
                                    )
                                    db.collection("users").document(firebaseUser.uid).set(userData)
                                        .addOnSuccessListener {
                                            navigateToMain()
                                        }
                                        .addOnFailureListener { e ->
                                            FirestoreUi.handleFailure(this, e, "LoginActivity")
                                        }
                                } else {
                                    navigateToMain()
                                }
                            }
                            .addOnFailureListener { e ->
                                FirestoreUi.handleFailure(this, e, "LoginActivity")
                            }
                    }
                } else {
                    Toast.makeText(this, R.string.authentication_failed, Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
