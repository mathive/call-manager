package com.callmanager.callmanager

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale

class SearchActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var rvLocalSearch: RecyclerView
    private lateinit var rvRemoteSearch: RecyclerView
    private lateinit var tvContactsHeader: TextView
    private lateinit var tvRemoteHeader: TextView
    private lateinit var btnShowMore: TextView
    private lateinit var llRemoteSearchAction: LinearLayout
    private lateinit var tvRemoteSearchLabel: TextView
    private lateinit var pbRemoteSearch: ProgressBar
    private lateinit var ivRemoteIcon: View
    private lateinit var divider: View

    private var localAdapter: CallLogAdapter? = null
    private var remoteAdapter: CallLogAdapter? = null
    private val localResults = mutableListOf<CallLogItem>()
    private val remoteResults = mutableListOf<CallLogItem>()
    
    private var searchJob: Job? = null
    private var remoteSearchJob: Job? = null
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var isAdmin = false
    private var isShowingAllLocal = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        initViews()
        setupRecyclerViews()
        setupListeners()
        fetchAdminStatus()

        // Focus and show keyboard
        etSearch.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun fetchAdminStatus() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get().addOnSuccessListener { doc ->
            isAdmin = doc.getString("role") == "Admin"
            Log.d("SearchActivity", "Admin Status Loaded: $isAdmin")
            val currentQuery = etSearch.text.toString()
            if (currentQuery.length >= 2) {
                updateRemoteActionUI(currentQuery)
            }
        }.addOnFailureListener {
            Log.e("SearchActivity", "Failed to fetch admin status", it)
        }
    }

    private fun initViews() {
        etSearch = findViewById(R.id.etSearch)
        rvLocalSearch = findViewById(R.id.rvLocalSearch)
        rvRemoteSearch = findViewById(R.id.rvRemoteSearch)
        tvContactsHeader = findViewById(R.id.tvContactsHeader)
        tvRemoteHeader = findViewById(R.id.tvRemoteHeader)
        btnShowMore = findViewById(R.id.btnShowMore)
        llRemoteSearchAction = findViewById(R.id.llRemoteSearch)
        tvRemoteSearchLabel = findViewById(R.id.tvRemoteSearchLabel)
        pbRemoteSearch = findViewById(R.id.pbRemoteSearch)
        ivRemoteIcon = findViewById(R.id.ivRemoteIcon)
        divider = findViewById(R.id.divider)

        findViewById<Toolbar>(R.id.searchToolbar).setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerViews() {
        rvLocalSearch.layoutManager = LinearLayoutManager(this)
        localAdapter = CallLogAdapter(localResults)
        rvLocalSearch.adapter = localAdapter

        rvRemoteSearch.layoutManager = LinearLayoutManager(this)
        remoteAdapter = CallLogAdapter(remoteResults)
        rvRemoteSearch.adapter = remoteAdapter
    }

    private fun setupListeners() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                isShowingAllLocal = false
                performSearch(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        llRemoteSearchAction.setOnClickListener {
            val query = etSearch.text.toString().trim()
            if (query.isNotEmpty()) {
                performRemoteDBSearch(query)
            }
        }

        btnShowMore.setOnClickListener {
            isShowingAllLocal = true
            performSearch(etSearch.text.toString())
        }
    }

    private fun performSearch(query: String) {
        searchJob?.cancel()
        val cleanQuery = query.trim()
        
        if (cleanQuery.length < 2) {
            clearResults()
            return
        }

        searchJob = lifecycleScope.launch {
            delay(300) // Debounce
            searchLocalContacts(cleanQuery)
            withContext(Dispatchers.Main) {
                updateRemoteActionUI(cleanQuery)
            }
        }
    }

    private fun updateRemoteActionUI(query: String) {
        // Requirement: 
        // 1. Admin can search anything (characters or short numbers like 9988).
        // 2. Guest can ONLY search if it's a valid number (at least 10 digits).
        
        val digitsOnly = query.filter { it.isDigit() }
        val isFullNumber = digitsOnly.length >= 10
        val isNumberQuery = query.all { it.isDigit() || it == '+' || it == ' ' }

        val shouldShowRemoteAction = if (isAdmin) {
            true // Admin can search any query >= 2 chars
        } else {
            // Guest can only search full numbers
            isNumberQuery && isFullNumber
        }
        
        if (shouldShowRemoteAction) {
            tvRemoteSearchLabel.text = "Search \"$query\" on Call Manager"
            llRemoteSearchAction.visibility = View.VISIBLE
            tvRemoteHeader.visibility = View.VISIBLE
            
            if (remoteSearchJob?.isActive != true) {
                ivRemoteIcon.visibility = View.VISIBLE
                pbRemoteSearch.visibility = View.GONE
                llRemoteSearchAction.isEnabled = true
            }
        } else {
            llRemoteSearchAction.visibility = View.GONE
            tvRemoteHeader.visibility = if (remoteResults.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    private suspend fun searchLocalContacts(query: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return

        val results = withContext(Dispatchers.IO) {
            val list = mutableListOf<CallLogItem>()
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
            val selectionArgs = arrayOf("%$query%", "%$query%")

            try {
                contentResolver.query(uri, projection, selection, selectionArgs, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val photoIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)

                    var count = 0
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIdx)
                        val number = cursor.getString(numIdx)
                        val photo = cursor.getString(photoIdx)
                        list.add(CallLogItem(name, number, "", 0, 1, photo))
                        count++
                        if (!isShowingAllLocal && count >= 5) break
                    }
                }
            } catch (e: Exception) {
                Log.e("SearchActivity", "Local contact search failed", e)
            }
            list
        }

        withContext(Dispatchers.Main) {
            localResults.clear()
            localResults.addAll(results)
            localAdapter?.notifyDataSetChanged()
            
            tvContactsHeader.visibility = if (results.isNotEmpty()) View.VISIBLE else View.GONE
            btnShowMore.visibility = if (results.size >= 5 && !isShowingAllLocal) View.VISIBLE else View.GONE
            divider.visibility = if (results.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun performRemoteDBSearch(query: String) {
        remoteSearchJob?.cancel()
        val cleanQuery = query.trim()
        
        val digitsOnly = cleanQuery.filter { it.isDigit() }
        val isFullNumber = digitsOnly.length >= 10
        val isNumberQuery = cleanQuery.all { it.isDigit() || it == '+' || it == ' ' }

        // Final security check
        if (!isAdmin && (!isNumberQuery || !isFullNumber)) {
            Toast.makeText(this, "Valid 10-digit number required for global search", Toast.LENGTH_SHORT).show()
            return
        }

        pbRemoteSearch.visibility = View.VISIBLE
        ivRemoteIcon.visibility = View.GONE
        llRemoteSearchAction.isEnabled = false
        
        remoteResults.clear()
        remoteAdapter?.notifyDataSetChanged()

        remoteSearchJob = lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                val list = mutableListOf<CallLogItem>()
                val searchTerms = mutableListOf<String>()
                
                searchTerms.add(cleanQuery)
                if (isAdmin && !isNumberQuery) {
                    searchTerms.add(cleanQuery.uppercase(Locale.getDefault()))
                    val capitalized = cleanQuery.lowercase(Locale.getDefault())
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                    if (!searchTerms.contains(capitalized)) searchTerms.add(capitalized)
                    val lower = cleanQuery.lowercase(Locale.getDefault())
                    if (!searchTerms.contains(lower)) searchTerms.add(lower)
                }

                try {
                    for (term in searchTerms) {
                        // 1. Users Collection
                        val userFields = if (isAdmin && !isNumberQuery) {
                            listOf("fullName", "name")
                        } else {
                            listOf("mobileNumber")
                        }

                        for (field in userFields) {
                            val snap: QuerySnapshot = db.collection("users")
                                .whereGreaterThanOrEqualTo(field, term)
                                .whereLessThanOrEqualTo(field, term + "\uf8ff")
                                .limit(10).get().await()
                            list.addAll(snap.documents.map { mapToCallLogItem(it) })
                        }

                        // 2. Global Spam Collection
                        val spamFields = if (isAdmin && !isNumberQuery) {
                            listOf("primaryName", "name")
                        } else {
                            listOf("documentId")
                        }

                        for (field in spamFields) {
                            val snap = if (field == "documentId") {
                                db.collection("global_spam")
                                    .whereGreaterThanOrEqualTo(FieldPath.documentId(), term)
                                    .whereLessThanOrEqualTo(FieldPath.documentId(), term + "\uf8ff")
                            } else {
                                db.collection("global_spam")
                                    .whereGreaterThanOrEqualTo(field, term)
                                    .whereLessThanOrEqualTo(field, term + "\uf8ff")
                            }
                            val snapResult = snap.limit(10).get().await()
                            list.addAll(snapResult.documents.map { mapToCallLogItem(it) })
                        }

                        // 3. Collection Group 'contacts'
                        val contactFields = if (isAdmin && !isNumberQuery) listOf("name") else listOf("number")
                        for (field in contactFields) {
                            try {
                                val contactSnap: QuerySnapshot = db.collectionGroup("contacts")
                                    .whereGreaterThanOrEqualTo(field, term)
                                    .whereLessThanOrEqualTo(field, term + "\uf8ff")
                                    .limit(30).get().await()
                                list.addAll(contactSnap.documents.map { mapToCallLogItem(it) })
                            } catch (e: Exception) {
                                Log.e("SearchActivity", "CollectionGroup search failed for $field", e)
                            }
                        }
                        
                        if (list.size >= 50) break
                    }
                    
                    // Standard Indian number fallback (Add +91 if missing) for Guests or number searches
                    if (isNumberQuery && list.isEmpty() && !cleanQuery.startsWith("+") && digitsOnly.length == 10) {
                        val withPrefix = "+91$cleanQuery"
                        val snapGroup: QuerySnapshot = db.collectionGroup("contacts")
                            .whereGreaterThanOrEqualTo("number", withPrefix)
                            .whereLessThanOrEqualTo("number", withPrefix + "\uf8ff")
                            .limit(20).get().await()
                        list.addAll(snapGroup.documents.map { mapToCallLogItem(it) })
                    }

                } catch (e: Exception) {
                    Log.e("SearchActivity", "Firestore search error", e)
                }
                
                list.distinctBy { it.number.replace("[^0-9+]".toRegex(), "") }
            }

            withContext(Dispatchers.Main) {
                pbRemoteSearch.visibility = View.GONE
                ivRemoteIcon.visibility = View.VISIBLE
                llRemoteSearchAction.isEnabled = true

                remoteResults.clear()
                remoteResults.addAll(results)
                remoteAdapter?.notifyDataSetChanged()
                
                if (results.isEmpty()) {
                    llRemoteSearchAction.visibility = View.VISIBLE
                    tvRemoteHeader.visibility = View.VISIBLE
                    Toast.makeText(this@SearchActivity, "No matches found in Call Manager", Toast.LENGTH_SHORT).show()
                } else {
                    llRemoteSearchAction.visibility = View.GONE
                    tvRemoteHeader.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun mapToCallLogItem(doc: DocumentSnapshot): CallLogItem {
        val name = doc.getString("name") ?: doc.getString("fullName") ?: doc.getString("primaryName") ?: "Unknown"
        val number = doc.getString("number") ?: doc.getString("mobileNumber") ?: if (doc.reference.path.contains("global_spam")) doc.id else ""
        val isSpam = doc.getBoolean("isVerifiedSpam") ?: false
        
        return CallLogItem(
            name = name,
            number = number,
            type = if (isSpam) "Verified" else "Community",
            time = 0,
            simId = 1,
            photoUri = null,
            dbName = name,
            isGlobalSpam = isSpam
        )
    }

    private fun clearResults() {
        localResults.clear()
        localAdapter?.notifyDataSetChanged()
        remoteResults.clear()
        remoteAdapter?.notifyDataSetChanged()
        
        tvContactsHeader.visibility = View.GONE
        tvRemoteHeader.visibility = View.GONE
        btnShowMore.visibility = View.GONE
        llRemoteSearchAction.visibility = View.GONE
        divider.visibility = View.GONE
    }
}
