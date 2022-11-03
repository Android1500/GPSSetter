package com.android1500.gpssetter

import android.app.Notification
import android.app.ProgressDialog
import android.location.Address
import android.location.Geocoder
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.app.NotificationCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android1500.gpssetter.adapter.FavListAdapter
import com.android1500.gpssetter.databinding.ActivityMapsBinding
import com.android1500.gpssetter.utils.NotificationsChannel
import com.android1500.gpssetter.utils.ext.getAddress
import com.android1500.gpssetter.utils.ext.isNetworkConnected
import com.android1500.gpssetter.utils.ext.showToast
import com.android1500.gpssetter.viewmodel.MainViewModel
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException
import java.util.regex.Pattern
import kotlin.properties.Delegates


@AndroidEntryPoint
class MapsActivity : AppCompatActivity(), OnMapReadyCallback, GoogleMap.OnMapClickListener{

    private lateinit var mMap: GoogleMap
    private val viewModel by viewModels<MainViewModel>()
    private val binding by lazy {
        ActivityMapsBinding.inflate(layoutInflater)
    }
    private val update by lazy {
        viewModel.getAvailableUpdate()
    }
    private val notificationsChannel by lazy {
        NotificationsChannel()
    }

    private var favListAdapter: FavListAdapter = FavListAdapter()
    private var mMarker: Marker? = null
    private var mLatLng: LatLng? = null
    private var lat by Delegates.notNull<Double>()
    private var lon by Delegates.notNull<Double>()
    private var xposedDialog: AlertDialog? = null
    private lateinit var alertDialog: MaterialAlertDialogBuilder
    private lateinit var dialog: AlertDialog
    private var addressList: List<Address>? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        initializeMap()
        setFloatActionButton()
        isModuleEnable()
        updateChecker()
    }

    private fun initializeMap(){
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this@MapsActivity)

    }

    private fun isModuleEnable(){
        viewModel.isXposed.observe(this){ isXposed ->
            xposedDialog?.dismiss()
            xposedDialog = null
            if (!isXposed){
                xposedDialog = MaterialAlertDialogBuilder(this).run {
                    setTitle(R.string.error_xposed_module_missing)
                    setMessage(R.string.error_xposed_module_missing_desc)
                    setCancelable(BuildConfig.DEBUG)
                    show()
                }
            }
        }

    }

    private fun setFloatActionButton() {
        if (viewModel.isStarted) {
            binding.start.visibility = View.GONE
            binding.stop.visibility = View.VISIBLE
        }

        binding.start.setOnClickListener {
            viewModel.update(true, lat, lon)
            mLatLng.let {
                mMarker?.position = it!!
            }
            mMarker?.isVisible = true
            binding.start.visibility = View.GONE
            binding.stop.visibility =View.VISIBLE
            lifecycleScope.launch {
                mLatLng?.getAddress(this@MapsActivity)?.let { address ->
                    address.collect{ value ->
                        showStartNotification(value)
                    }
                }
            }
           showToast(getString(R.string.location_set))
        }
        binding.stop.setOnClickListener {
            mLatLng.let {
                viewModel.update(false, it!!.latitude, it.longitude)
            }
            mMarker?.isVisible = false
            binding.stop.visibility = View.GONE
            binding.start.visibility = View.VISIBLE
            cancelNotification()
            showToast(getString(R.string.location_unset))

        }
    }


    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        with(mMap){
            val zoom = 12.0f
            lat = viewModel.getLat
            lon  = viewModel.getLng
            mLatLng = LatLng(lat, lon)
            mLatLng.let {
                mMarker = addMarker(
                    MarkerOptions().position(it!!).draggable(false)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)).visible(false)
                )
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, zoom))

            }
            setOnMapClickListener(this@MapsActivity)

            if (viewModel.isStarted){
                mMarker?.let {
                    it.isVisible = true
                    it.showInfoWindow()
                }
            }
        }

    }

    override fun onMapClick(p0: LatLng) {
            mLatLng = p0
            mMarker?.let { marker ->
                mLatLng.let {
                    marker.position = it!!
                    marker.isVisible = true
                    mMap.animateCamera(CameraUpdateFactory.newLatLng(it))
                    lat = it.latitude
                    lon = it.longitude
                }
            }

    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu,menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        when(item.itemId){
            R.id.about -> aboutDialog()
            R.id.add_fav -> addFavouriteDialog()
            R.id.get_favourite -> openFavouriteListDialog()
            R.id.search -> searchDialog()

       }
        return super.onOptionsItemSelected(item)
    }

    private inline fun moveMapToNewLocation(moveNewLocation: Boolean) {
        if (moveNewLocation) {
            mLatLng = LatLng(lat, lon)
            mLatLng.let { latLng ->
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng!!, 12.0f))
                mMarker?.apply {
                    position = latLng
                    isVisible = true
                    showInfoWindow()
                }

            }
        }

    }

    override fun onResume() {
        super.onResume()
        viewModel.updateXposedState()
    }


    private fun aboutDialog(){
        alertDialog = MaterialAlertDialogBuilder(this)
        layoutInflater.inflate(R.layout.about,null).apply {
            val  tittle = findViewById<TextView>(R.id.design_about_title)
            val  version = findViewById<TextView>(R.id.design_about_version)
            val  info = findViewById<TextView>(R.id.design_about_info)
            tittle.text = getString(R.string.app_name)
            version.text = BuildConfig.VERSION_NAME
            info.text = getString(R.string.about_info)
        }.run {
            alertDialog.setView(this)
            alertDialog.show()
        }


    }


    private fun searchDialog(){
            alertDialog = MaterialAlertDialogBuilder(this)
            val view = layoutInflater.inflate(R.layout.search_layout,null)
            val editText = view.findViewById<EditText>(R.id.search_edittxt)
            val progressBar = ProgressDialog(this)
            progressBar.setMessage("searching...")
            alertDialog.setTitle("Search")
            alertDialog.setView(view)
            alertDialog.setPositiveButton("Search") { _, _ ->
                if (isNetworkConnected()){
                    lifecycleScope.launch(Dispatchers.Main) {
                        val  getInput = editText.text.toString()
                        if (getInput.isNotEmpty()){
                            getSearchAddress(getInput).let {
                                it.collect { value ->
                                    when(value) {
                                        is SearchProgress.Progress -> {
                                            progressBar.show()
                                        }
                                        is SearchProgress.Complete -> {
                                            val address = value.address
                                            val split = address.split(",")
                                            lat = split[0].toDouble()
                                            lon = split[1].toDouble()
                                            progressBar.cancel()
                                            moveMapToNewLocation(true)
                                        }
//
                                        is SearchProgress.Fail -> {
                                            showToast(value.error!!)
                                            progressBar.cancel()
                                        }

                                    }

                                }
                            }
                        }
                    }
                } else {
                    showToast("Internet not connected")
                }
            }
            alertDialog.show()

    }

    private fun addFavouriteDialog(){

           alertDialog =  MaterialAlertDialogBuilder(this).apply {
                   val view = layoutInflater.inflate(R.layout.search_layout,null)
                   val editText = view.findViewById<EditText>(R.id.search_edittxt)
                   setTitle(getString(R.string.add_fav_dialog_title))
                   setPositiveButton(getString(R.string.dialog_button_add)) { _, _ ->
                       val s = editText.text.toString()
                       if (!mMarker?.isVisible!!){
                           showToast("Not location select")
                       }else{
                           viewModel.storeFavorite(s, lat, lon)
                           viewModel.response.observe(this@MapsActivity){
                               if (it == (-1).toLong()) showToast("Can't save") else showToast("Save")
                           }
                       }
               }
               setView(view)
               show()
       }

   }

    private fun openFavouriteListDialog() {
        getAllUpdatedFavList()
        alertDialog = MaterialAlertDialogBuilder(this@MapsActivity)
        alertDialog.setTitle(getString(R.string.title_fav))
        val view = layoutInflater.inflate(R.layout.fav,null)
        val  rcv = view.findViewById<RecyclerView>(R.id.favorites_list)
        rcv.layoutManager = LinearLayoutManager(this)
        rcv.adapter = favListAdapter
        favListAdapter.onItemClick = {
            it.let {
                lat = it.lat!!
                lon = it.lng!!
            }
            moveMapToNewLocation(true)
            if (dialog.isShowing) dialog.dismiss()

        }
        favListAdapter.onItemDelete = {
            viewModel.deleteFavourite(it)
        }
        alertDialog.setView(view)
        dialog = alertDialog.create()
        dialog.show()

        }


    private fun getAllUpdatedFavList(){
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){
                viewModel.doGetUserDetails()
                viewModel.allFavList.collect {
                    favListAdapter.submitList(it)
                }
            }
        }

    }


    private fun updateDialog(){
        alertDialog = MaterialAlertDialogBuilder(this@MapsActivity)
        alertDialog.setTitle(R.string.snackbar_update)
        alertDialog.setMessage(update?.changelog)
        alertDialog.setPositiveButton(getString(R.string.update)) { _, _ ->
            MaterialAlertDialogBuilder(this).apply {
                val view = layoutInflater.inflate(R.layout.update_dialog, null)
                val progress = view.findViewById<LinearProgressIndicator>(R.id.update_download_progress)
                val cancel = view.findViewById<AppCompatButton>(R.id.update_download_cancel)
                setView(view)
                cancel.setOnClickListener {
                    viewModel.cancelDownload(this@MapsActivity)
                    dialog.dismiss()
                }
                lifecycleScope.launch {
                    viewModel.downloadState.collect {
                        when (it) {
                            is MainViewModel.State.Downloading -> {
                                if (it.progress > 0) {
                                    progress.isIndeterminate = false
                                    progress.progress = it.progress
                                }
                            }
                            is MainViewModel.State.Done -> {
                                viewModel.openPackageInstaller(this@MapsActivity, it.fileUri)
                                viewModel.clearUpdate()
                                dialog.dismiss()
                            }

                            is MainViewModel.State.Failed -> {
                                Toast.makeText(
                                    this@MapsActivity,
                                    R.string.bs_update_download_failed,
                                    Toast.LENGTH_LONG
                                ).show()
                                dialog.dismiss()

                            }
                            else -> {}
                        }
                    }
                }
                update?.let { it ->
                    viewModel.startDownload(this@MapsActivity, it)
                } ?: run {
                    dialog.dismiss()
                }
            }.run {
                dialog = create()
                dialog.show()
            }
        }
        dialog = alertDialog.create()
        dialog.show()

    }

    private fun updateChecker(){
        lifecycleScope.launchWhenResumed {
            viewModel.update.collect{
                if (it!= null){
                    updateDialog()
                }
            }
        }
    }

    private fun showStartNotification(address: String){
        notificationsChannel.showNotification(this){
            it.setSmallIcon(R.drawable.ic_stop)
            it.setContentTitle(getString(R.string.location_set))
            it.setContentText(address)
            it.setAutoCancel(true)
            it.setCategory(Notification.CATEGORY_EVENT)
            it.priority = NotificationCompat.PRIORITY_HIGH
        }

    }


    private fun cancelNotification(){
       notificationsChannel.cancelAllNotifications(this)
    }


    private suspend fun getSearchAddress(address: String) = callbackFlow {
        withContext(Dispatchers.IO){
            trySend(SearchProgress.Progress)
            if (isRegexMatch(address)){
                delay(3000)
                trySend(SearchProgress.Complete(address))
            }
            try {
                val geocoder = Geocoder(this@MapsActivity)
                addressList = geocoder.getFromLocationName(address,5)
                val list: List<Address>? = addressList
                if (list == null) {
                    trySend(SearchProgress.Fail(null))
                }
                if (list?.size == 1){
                    val sb = StringBuilder()
                    sb.append(list[0].latitude)
                    sb.append(",")
                    sb.append(list[0].longitude)
                    delay(3000)
                    trySend(SearchProgress.Complete(sb.toString()))
                } else {
                    if (addressList?.size != 0) {
                        trySend(SearchProgress.Fail(null))
                    }
                    trySend(SearchProgress.Fail("Address not found"))
                }
            } catch (io : IOException){
                io.printStackTrace()
                trySend(SearchProgress.Fail("No internet"))
            }
        }

        awaitClose { this.cancel() }

    }


    private fun isRegexMatch(str: String?): Boolean {
        return Pattern.matches(
            "[-+]?\\d{1,3}([.]\\d+)?, *[-+]?\\d{1,3}([.]\\d+)?",
            str!!
        )
    }


}

sealed class SearchProgress {
    object Progress : SearchProgress()
    data class Complete(val address: String) : SearchProgress()
    data class Fail(val error: String?) : SearchProgress()

}










