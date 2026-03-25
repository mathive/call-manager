package com.callmanager.callmanager

import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class ProfileActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var swBlockUnknown: SwitchMaterial
    private lateinit var swBlockWhatsappUnknown: SwitchMaterial
    private lateinit var swEdgeGlow: SwitchMaterial
    private var updatingSwitches = false
    private var pendingEnableBlockUnknown = false
    private var pendingEnableWhatsappUnknown = false
    private var pendingEnableEdgeGlow = false
    private val requestCallScreeningRole =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            handlePendingSettingsRefresh()
        }

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

        swBlockUnknown = findViewById(R.id.swBlockUnknown)
        swBlockWhatsappUnknown = findViewById(R.id.swBlockWhatsappUnknown)
        swEdgeGlow = findViewById(R.id.swEdgeGlow)

        val prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        syncSettingSwitches()

        swBlockUnknown.setOnCheckedChangeListener { _, isChecked ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            if (isChecked && !hasCallScreeningAccess()) {
                pendingEnableBlockUnknown = true
                setSwitchChecked(swBlockUnknown, false)
                showCallScreeningPermissionDialog()
                return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean("block_all_unknown", isChecked).apply()
            val message = if (isChecked) {
                getString(R.string.blocking_all_unknown_calls)
            } else {
                getString(R.string.block_unknown_disabled)
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        swBlockWhatsappUnknown.setOnCheckedChangeListener { _, isChecked ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            if (isChecked && !isNotificationServiceEnabled()) {
                pendingEnableWhatsappUnknown = true
                setSwitchChecked(swBlockWhatsappUnknown, false)
                showNotificationPermissionDialog()
                return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean("block_whatsapp_unknown", isChecked).apply()
            val message = if (isChecked) {
                getString(R.string.blocking_unknown_whatsapp_calls)
            } else {
                getString(R.string.block_whatsapp_unknown_disabled)
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

            if (isChecked && !hasNotificationPolicyAccess()) {
                showNotificationPolicyPermissionDialog()
            }
        }

        swEdgeGlow.setOnCheckedChangeListener { _, isChecked ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            if (isChecked && !Settings.canDrawOverlays(this)) {
                pendingEnableEdgeGlow = true
                setSwitchChecked(swEdgeGlow, false)
                showOverlayPermissionDialog()
                return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean("edge_glow_enabled", isChecked).apply()
            val message = if (isChecked) {
                getString(R.string.edge_glow_alert_enabled)
            } else {
                getString(R.string.edge_glow_alert_disabled)
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        val currentUser = auth.currentUser
        if (currentUser == null) {
            finish()
            return
        }

        db.collection("users").document(currentUser.uid).get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    val fullName = document.getString("name")
                        ?: document.getString("fullName")
                        ?: getString(R.string.default_user_name)
                    val mobileNumber = document.getString("mobile")
                        ?: document.getString("mobileNumber")
                        ?: getString(R.string.not_provided)
                    val email = document.getString("email") ?: currentUser.email ?: getString(R.string.no_email_available)
                    val role = document.getString("role") ?: "Guest"

                    tvName.text = fullName
                    tvPhone.text = mobileNumber
                    tvEmail.text = email
                    tvStatus.text = if (role == "Admin") {
                        getString(R.string.administrator)
                    } else {
                        getString(R.string.verified_user)
                    }

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
                Toast.makeText(this, "Failed to load profile: ${e.message}", Toast.LENGTH_SHORT).show()
            }

        btnLogout.setOnClickListener {
            auth.signOut()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        handlePendingSettingsRefresh()
    }

    private fun handlePendingSettingsRefresh() {
        val prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)

        if (pendingEnableBlockUnknown) {
            val granted = hasCallScreeningAccess()
            prefs.edit().putBoolean("block_all_unknown", granted).apply()
            if (!granted) {
                Toast.makeText(this, R.string.permission_not_granted, Toast.LENGTH_SHORT).show()
            }
            pendingEnableBlockUnknown = false
        }

        if (pendingEnableWhatsappUnknown) {
            val granted = isNotificationServiceEnabled()
            prefs.edit().putBoolean("block_whatsapp_unknown", granted).apply()
            if (granted && !hasNotificationPolicyAccess()) {
                showNotificationPolicyPermissionDialog()
            } else if (!granted) {
                Toast.makeText(this, R.string.permission_not_granted, Toast.LENGTH_SHORT).show()
            }
            pendingEnableWhatsappUnknown = false
        }

        if (pendingEnableEdgeGlow) {
            val granted = Settings.canDrawOverlays(this)
            prefs.edit().putBoolean("edge_glow_enabled", granted).apply()
            if (!granted) {
                Toast.makeText(this, R.string.permission_not_granted, Toast.LENGTH_SHORT).show()
            }
            pendingEnableEdgeGlow = false
        }

        syncSettingSwitches()
    }

    private fun syncSettingSwitches() {
        val prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        updatingSwitches = true
        swBlockUnknown.isChecked =
            prefs.getBoolean("block_all_unknown", false) && hasCallScreeningAccess()
        swBlockWhatsappUnknown.isChecked =
            prefs.getBoolean("block_whatsapp_unknown", false) && isNotificationServiceEnabled()
        swEdgeGlow.isChecked =
            prefs.getBoolean("edge_glow_enabled", false) && Settings.canDrawOverlays(this)
        updatingSwitches = false
    }

    private fun setSwitchChecked(switch: SwitchMaterial, checked: Boolean) {
        updatingSwitches = true
        switch.isChecked = checked
        updatingSwitches = false
    }

    private fun hasCallScreeningAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true
        }
        val roleManager = getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    private fun hasNotificationPolicyAccess(): Boolean {
        val notificationManager = getSystemService(NotificationManager::class.java) ?: return false
        return notificationManager.isNotificationPolicyAccessGranted
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!android.text.TextUtils.isEmpty(flat)) {
            val names = flat.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            for (name in names) {
                val cn = android.content.ComponentName.unflattenFromString(name)
                if (cn != null && android.text.TextUtils.equals(pkgName, cn.packageName)) {
                    return true
                }
            }
        }
        return false
    }

    private fun showCallScreeningPermissionDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.call_screening_permission_title)
            .setMessage(R.string.call_screening_permission_message)
            .setPositiveButton(R.string.enable) { _, _ ->
                requestCallScreeningAccess()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    private fun showNotificationPolicyPermissionDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.notification_permission_title)
            .setMessage(R.string.notification_policy_permission_message)
            .setPositiveButton(R.string.enable) { _, _ ->
                openSettings(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showOverlayPermissionDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.overlay_permission_title)
            .setMessage(R.string.overlay_permission_message)
            .setPositiveButton(R.string.enable) { _, _ ->
                openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                setSwitchChecked(swEdgeGlow, false)
            }
            .show()
    }

    private fun requestCallScreeningAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val roleManager = getSystemService(RoleManager::class.java) ?: return
        if (roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            handlePendingSettingsRefresh()
            return
        }
        val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
        requestCallScreeningRole.launch(intent)
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.permission_not_granted, Toast.LENGTH_SHORT).show()
        }
    }
}
