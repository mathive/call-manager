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
import com.google.firebase.firestore.FirebaseFirestoreException
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

        etSearch.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onResume() {
        super.onResume()
        FirestoreUi.showPendingMessageIfAny(this)
    }

    private fun fetchAdminStatus() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get().addOnSuccessListener { doc ->
            isAdmin = doc.getString("role").equals("Admin", ignoreCase = true)
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
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                isShowingAllLocal = false
                performSearch(s.toString())
            }
            override fun afterTextChanged(s: Editable?) = Unit
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
            delay(300)
            searchLocalContacts(cleanQuery)
            withContext(Dispatchers.Main) {
                updateRemoteActionUI(cleanQuery)
            }
        }
    }

    private fun updateRemoteActionUI(query: String) {
        val digitsOnly = query.filter { it.isDigit() }
        val isFullNumber = digitsOnly.length >= 10
        val isNumberQuery = query.all { it.isDigit() || it == '+' || it == ' ' }

        val shouldShowRemoteAction = if (isAdmin) {
            true
        } else {
            query.length >= 2
        }

        if (shouldShowRemoteAction) {
            tvRemoteSearchLabel.text = getString(R.string.remote_search_prompt, query)
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
                contentResolver.query(uri, projection, selection, selectionArgs, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")
                    ?.use { cursor ->
                        val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        val photoIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)

                        var count = 0
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(nameIdx).orEmpty()
                            val number = cursor.getString(numIdx).orEmpty()
                            val photo = cursor.getString(photoIdx)
                            list.add(
                                CallLogItem(
                                    name = name,
                                    number = number,
                                    type = "Unknown",
                                    time = 0L,
                                    simId = 1,
                                    photoUri = photo,
                                    lookupSource = "user_contact"
                                )
                            )
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
            localAdapter?.updateData(results)

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

        if (!isAdmin && (!isNumberQuery || !isFullNumber)) {
            Toast.makeText(this, R.string.valid_ten_digit_required, Toast.LENGTH_SHORT).show()
            return
        }

        pbRemoteSearch.visibility = View.VISIBLE
        ivRemoteIcon.visibility = View.GONE
        llRemoteSearchAction.isEnabled = false

        remoteResults.clear()
        remoteAdapter?.updateData(emptyList())

        remoteSearchJob = lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                val list = mutableListOf<CallLogItem>()
                val searchTerms = mutableListOf<String>()
                val numberVariants = if (isNumberQuery) buildNumberSearchVariants(cleanQuery) else emptyList()
                val numberPrefixTerms = if (isNumberQuery) buildNumberPrefixTerms(cleanQuery) else emptyList()

                searchTerms.add(cleanQuery)
                numberPrefixTerms.forEach { term ->
                    if (!searchTerms.contains(term)) {
                        searchTerms.add(term)
                    }
                }
                if (isAdmin && !isNumberQuery) {
                    searchTerms.add(cleanQuery.uppercase(Locale.getDefault()))
                    val capitalized = cleanQuery.lowercase(Locale.getDefault())
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                    if (!searchTerms.contains(capitalized)) searchTerms.add(capitalized)
                    val lower = cleanQuery.lowercase(Locale.getDefault())
                    if (!searchTerms.contains(lower)) searchTerms.add(lower)
                }

                try {
                    if (numberVariants.isNotEmpty()) {
                        val userNumberFields = listOf("mobile", "mobileNumber")
                        for (field in userNumberFields) {
                            val userSnap = db.collection("users")
                                .whereIn(field, numberVariants)
                                .limit(10)
                                .get()
                                .await()
                            list.addAll(userSnap.documents.mapNotNull { mapToCallLogItem(it, "verified") })
                        }

                        val spamDocSnap = db.collection("global_spam")
                            .whereIn(FieldPath.documentId(), numberVariants)
                            .limit(10)
                            .get()
                            .await()
                        list.addAll(spamDocSnap.documents.mapNotNull { mapToCallLogItem(it, "spam") })

                        val spamNumberFields = listOf("phoneNumber", "number")
                        for (field in spamNumberFields) {
                            val spamSnap = db.collection("global_spam")
                                .whereIn(field, numberVariants)
                                .limit(10)
                                .get()
                                .await()
                            list.addAll(spamSnap.documents.mapNotNull { mapToCallLogItem(it, "spam") })
                        }

                        val contactDocIds = numberVariants.map { it.removePrefix("+") }.distinct().take(10)
                        val fallbackResults = fallbackSearchAllUsersContactsByDocId(contactDocIds)
                        Log.d("SearchActivity", "fallbackUserContacts query=$cleanQuery ids=$contactDocIds count=${fallbackResults.size}")
                        list.addAll(fallbackResults)

                        if (fallbackResults.isEmpty()) {
                            val contactNumberFields = listOf("phoneNumber", "number")
                            for (field in contactNumberFields) {
                                try {
                                    val contactSnap = db.collectionGroup("contacts")
                                        .whereIn(field, numberVariants)
                                        .limit(30)
                                        .get()
                                        .await()
                                    Log.d("SearchActivity", "contactFieldMatches query=$cleanQuery field=$field variants=$numberVariants count=${contactSnap.documents.size}")
                                    list.addAll(contactSnap.documents.mapNotNull { mapToCallLogItem(it, "user_contact") })
                                } catch (e: Exception) {
                                    if (isMissingContactsCollectionGroupIndex(e)) {
                                        Log.w("SearchActivity", "Skipping indexed contacts collectionGroup lookup for field=$field because the required Firestore index is missing", e)
                                    } else {
                                        throw e
                                    }
                                }
                            }
                        }
                    }

                    for (term in searchTerms) {
                        val userFields = if (isAdmin && !isNumberQuery) {
                            listOf("name")
                        } else {
                            listOf("mobile", "mobileNumber")
                        }

                        for (field in userFields) {
                            val snap = db.collection("users")
                                .whereGreaterThanOrEqualTo(field, term)
                                .whereLessThanOrEqualTo(field, term + "\uf8ff")
                                .limit(10)
                                .get()
                                .await()
                            list.addAll(snap.documents.mapNotNull { mapToCallLogItem(it, "verified") })
                        }

                        val spamFields = if (isAdmin && !isNumberQuery) {
                            listOf("primaryName", "names")
                        } else {
                            listOf("documentId")
                        }

                        for (field in spamFields) {
                            val spamQuery = if (field == "documentId") {
                                db.collection("global_spam")
                                    .whereGreaterThanOrEqualTo(FieldPath.documentId(), term)
                                    .whereLessThanOrEqualTo(FieldPath.documentId(), term + "\uf8ff")
                            } else {
                                db.collection("global_spam")
                                    .whereGreaterThanOrEqualTo(field, term)
                                    .whereLessThanOrEqualTo(field, term + "\uf8ff")
                            }
                            val snapResult = spamQuery.limit(10).get().await()
                            list.addAll(snapResult.documents.mapNotNull { mapToCallLogItem(it, "spam") })
                        }

                        val contactFields = if (isAdmin && !isNumberQuery) listOf("name") else listOf("number")
                        for (field in contactFields) {
                            try {
                                val contactSnap: QuerySnapshot = db.collectionGroup("contacts")
                                    .whereGreaterThanOrEqualTo(field, term)
                                    .whereLessThanOrEqualTo(field, term + "\uf8ff")
                                    .limit(30)
                                    .get()
                                    .await()
                                list.addAll(contactSnap.documents.mapNotNull { mapToCallLogItem(it, "user_contact") })
                            } catch (e: Exception) {
                                Log.e("SearchActivity", "CollectionGroup search failed for $field", e)
                            }
                        }

                        if (list.size >= 50) break
                    }

                } catch (e: Exception) {
                    Log.e("SearchActivity", "Firestore search error", e)
                    withContext(Dispatchers.Main) {
                        FirestoreUi.handleFailure(this@SearchActivity, e, "SearchActivity")
                    }
                }

                list.distinctBy { normalizeResultNumber(it.number) ?: it.number }
            }

            withContext(Dispatchers.Main) {
                pbRemoteSearch.visibility = View.GONE
                ivRemoteIcon.visibility = View.VISIBLE
                llRemoteSearchAction.isEnabled = true

                remoteResults.clear()
                remoteResults.addAll(results)
                remoteAdapter?.updateData(results)

                if (results.isEmpty()) {
                    llRemoteSearchAction.visibility = View.VISIBLE
                    tvRemoteHeader.visibility = View.VISIBLE
                    Toast.makeText(this@SearchActivity, R.string.no_matches_found, Toast.LENGTH_SHORT).show()
                } else {
                    llRemoteSearchAction.visibility = View.GONE
                    tvRemoteHeader.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun buildNumberSearchVariants(query: String): List<String> {
        return PhoneNumberVariants.buildFirestoreDocumentIds(query).take(10)
    }

    private fun buildNumberPrefixTerms(query: String): List<String> {
        val digits = PhoneNumberVariants.digitsOnly(query)
        if (digits.isBlank()) return emptyList()

        val terms = linkedSetOf<String>()
        terms += digits

        when {
            digits.length <= 10 -> {
                terms += "91$digits"
                terms += "+91$digits"
                terms += "0$digits"
            }
            digits.length == 11 && digits.startsWith("0") -> {
                val local = digits.substring(1)
                terms += local
                terms += "91$local"
                terms += "+91$local"
            }
            digits.length >= 11 && digits.startsWith("91") -> {
                val local = digits.removePrefix("91")
                if (local.isNotBlank()) {
                    terms += local
                    terms += "+$digits"
                }
            }
        }

        return terms.toList().take(10)
    }

    private fun mapToCallLogItem(doc: DocumentSnapshot, lookupSource: String): CallLogItem? {
        val number = normalizeResultNumber(
            doc.getString("number"),
            doc.getString("phoneNumber"),
            doc.getString("mobile"),
            doc.getString("mobileNumber"),
            if (lookupSource == "spam") doc.id else null
        ) ?: return null

        val name = doc.getString("name")
            ?: doc.getString("fullName")
            ?: doc.getString("primaryName")
            ?: doc.getString("names")
            ?: number

        return CallLogItem(
            name = name,
            number = number,
            type = "Unknown",
            time = 0L,
            simId = 1,
            photoUri = null,
            lookupSource = lookupSource
        )
    }

    private suspend fun fallbackSearchAllUsersContactsByDocId(contactDocIds: List<String>): List<CallLogItem> {
        if (contactDocIds.isEmpty()) return emptyList()

        val results = mutableListOf<CallLogItem>()
        val userSnapshots = db.collection("users")
            .limit(200)
            .get()
            .await()
        Log.d("SearchActivity", "fallbackUserContacts usersFetched=${userSnapshots.documents.size} ids=$contactDocIds")

        for (userDoc in userSnapshots.documents) {
            for (contactDocId in contactDocIds) {
                val contactDoc = userDoc.reference
                    .collection("contacts")
                    .document(contactDocId)
                    .get()
                    .await()

                if (contactDoc.exists()) {
                    Log.d("SearchActivity", "fallbackUserContacts hit user=${userDoc.id} contactDocId=$contactDocId")
                    mapToCallLogItem(contactDoc, "user_contact")?.let(results::add)
                }
            }
            if (results.size >= 30) {
                break
            }
        }

        return results
    }

    private fun isMissingContactsCollectionGroupIndex(error: Throwable): Boolean {
        val firestoreError = error as? FirebaseFirestoreException ?: return false
        if (firestoreError.code != FirebaseFirestoreException.Code.FAILED_PRECONDITION) {
            return false
        }
        val message = firestoreError.message.orEmpty()
        return "collection contacts" in message && "index" in message.lowercase(Locale.getDefault())
    }

    private fun normalizeResultNumber(vararg candidates: String?): String? {
        val raw = candidates.firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotBlank) } ?: return null
        return PhoneNumberVariants.toIndianMobilePlus(raw)
            ?: PhoneNumberVariants.digitsOnly(raw).takeIf { it.isNotBlank() }?.let { "+$it" }
    }

    private fun clearResults() {
        localResults.clear()
        localAdapter?.updateData(emptyList())
        remoteResults.clear()
        remoteAdapter?.updateData(emptyList())

        tvContactsHeader.visibility = View.GONE
        tvRemoteHeader.visibility = View.GONE
        btnShowMore.visibility = View.GONE
        llRemoteSearchAction.visibility = View.GONE
        divider.visibility = View.GONE
    }
}
