package com.callmanager.callmanager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
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
            prefs.edit().putBoolean("block_all_unknown", isChecked).apply()
            val message = if (isChecked) "Blocking all unknown calls" else "Block unknown disabled"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        swBlockWhatsappUnknown.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("block_whatsapp_unknown", isChecked).apply()
            val message = if (isChecked) "Blocking unknown WhatsApp calls" else "Block WhatsApp unknown disabled"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            
            if (isChecked && !isNotificationServiceEnabled()) {
                showNotificationPermissionDialog()
            }
        }

        swEdgeGlow.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("edge_glow_enabled", isChecked).apply()
            val message = if (isChecked) "Edge glow alert enabled" else "Edge glow alert disabled"
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
                    val mobileNumber = document.getString("mobileNumber") ?: "Not provided"
                    val email = document.getString("email") ?: currentUser.email ?: ""
                    val role = document.getString("role") ?: "Guest"

                    tvName.text = fullName
                    tvPhone.text = mobileNumber
                    tvEmail.text = email
                    tvStatus.text = if (role == "Admin") "Administrator" else "Verified User"

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
            .setTitle("Permission Required")
            .setMessage("To block WhatsApp calls, this app needs Notification Access. Please enable it in the next screen.")
            .setPositiveButton("Enable") { _, _ ->
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showOverlayPermissionDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("Edge glow alert requires 'Display over other apps' permission to show the visual alert. Please enable it in settings.")
            .setPositiveButton("Enable") { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            }
            .setNegativeButton("Cancel") { _, _ ->
                findViewById<SwitchMaterial>(R.id.swEdgeGlow).isChecked = false
            }
            .show()
    }
}
