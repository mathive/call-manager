package com.callmanager.callmanager

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.edit
import androidx.core.net.toUri
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class ProfileActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.setNavigationOnClickListener { finish() }

        val ivProfile: ShapeableImageView = findViewById(R.id.ivUserProfile)
        val tvName: TextView = findViewById(R.id.tvUserName)
        val tvPhone: TextView = findViewById(R.id.tvUserPhone)
        val tvEmail: TextView = findViewById(R.id.tvUserEmail)
        val tvStatus: TextView = findViewById(R.id.tvUserStatus)
        val btnLogout: MaterialButton = findViewById(R.id.btnLogout)
        
        val swBlockUnknown: SwitchMaterial = findViewById(R.id.swBlockUnknown)
        val swBlockWhatsappUnknown: SwitchMaterial = findViewById(R.id.swBlockWhatsappUnknown)
        val swEdgeGlow: SwitchMaterial = findViewById(R.id.swEdgeGlow)

        // Load saved settings
        val prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        swBlockUnknown.isChecked = prefs.getBoolean("block_all_unknown", false)
        swBlockWhatsappUnknown.isChecked = prefs.getBoolean("block_whatsapp_unknown", false)
        swEdgeGlow.isChecked = prefs.getBoolean("edge_glow_enabled", false)

        swBlockUnknown.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("block_all_unknown", isChecked) }
            val message = if (isChecked) getString(R.string.blocking_all_unknown_calls) else getString(R.string.block_unknown_disabled)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        swBlockWhatsappUnknown.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("block_whatsapp_unknown", isChecked) }
            val message = if (isChecked) getString(R.string.blocking_unknown_whatsapp_calls) else getString(R.string.block_whatsapp_unknown_disabled)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            
            if (isChecked && !isNotificationServiceEnabled()) {
                showNotificationPermissionDialog()
            }
        }

        swEdgeGlow.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("edge_glow_enabled", isChecked) }
            val message = if (isChecked) getString(R.string.edge_glow_alert_enabled) else getString(R.string.edge_glow_alert_disabled)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

            if (isChecked && !Settings.canDrawOverlays(this)) {
                showOverlayPermissionDialog()
            }
        }

        val currentUser = auth.currentUser
        if (currentUser == null) {
            finish()
            return
        }

        // Fetch user details from Firestore
        db.collection("users").document(currentUser.uid).get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    val fullName = document.getString("fullName") ?: "User"
                    val mobileNumber = document.getString("mobileNumber") ?: getString(R.string.not_provided)
                    val email = document.getString("email") ?: currentUser.email ?: ""
                    val role = document.getString("role") ?: "Guest"

                    getSharedPreferences("UserProfileCache", Context.MODE_PRIVATE).edit {
                        putString("uid", currentUser.uid)
                        putString("fullName", fullName)
                        putString("email", email)
                        putString("mobileNumber", if (mobileNumber == getString(R.string.not_provided)) "" else mobileNumber)
                        putString("role", role)
                    }

                    tvName.text = fullName
                    tvPhone.text = mobileNumber
                    tvEmail.text = email
                    tvStatus.text = if (role == "Admin") getString(R.string.administrator) else getString(R.string.verified_user)

                    // Use Google Profile pic if available
                    val photoUrl = currentUser.photoUrl
                    if (photoUrl != null) {
                        Glide.with(this)
                            .load(photoUrl)
                            .circleCrop()
                            .into(ivProfile)
                    }
                }
            }
            .addOnFailureListener { e ->
                FirestoreUi.handleFailure(this, e, "ProfileActivity")
            }

        btnLogout.setOnClickListener {
            auth.signOut()
            getSharedPreferences("UserProfileCache", Context.MODE_PRIVATE).edit { clear() }
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        FirestoreUi.showPendingMessageIfAny(this)
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!android.text.TextUtils.isEmpty(flat)) {
            val names = flat.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            for (i in names.indices) {
                val cn = android.content.ComponentName.unflattenFromString(names[i])
                if (cn != null) {
                    if (android.text.TextUtils.equals(pkgName, cn.packageName)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun showNotificationPermissionDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.notification_permission_title)
            .setMessage(R.string.notification_permission_message)
            .setPositiveButton(R.string.enable) { _, _ ->
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showOverlayPermissionDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.notification_permission_title)
            .setMessage(R.string.overlay_permission_message)
            .setPositiveButton(R.string.enable) { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
                startActivity(intent)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                findViewById<SwitchMaterial>(R.id.swEdgeGlow).isChecked = false
            }
            .show()
    }
}
