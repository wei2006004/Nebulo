package com.frostnerd.smokescreen.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.frostnerd.dnstunnelproxy.DnsServerInformation
import com.frostnerd.networking.NetworkUtil
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.frostnerd.encrypteddnstunnelproxy.AbstractHttpsDNSHandle
import com.frostnerd.encrypteddnstunnelproxy.HttpsDnsServerInformation
import com.frostnerd.encrypteddnstunnelproxy.ServerConfiguration
import com.frostnerd.encrypteddnstunnelproxy.createSimpleServerConfig
import com.frostnerd.smokescreen.*
import com.frostnerd.smokescreen.activity.BackgroundVpnConfigureActivity
import com.frostnerd.smokescreen.activity.MainActivity
import com.frostnerd.smokescreen.util.Notifications
import com.frostnerd.smokescreen.util.proxy.ProxyHandler
import com.frostnerd.smokescreen.util.proxy.SmokeProxy
import com.frostnerd.vpntunnelproxy.TrafficStats
import com.frostnerd.vpntunnelproxy.VPNTunnelProxy
import java.io.Serializable
import java.lang.IllegalArgumentException


/**
 * Copyright Daniel Wolf 2018
 * All rights reserved.
 * Code may NOT be used without proper permission, neither in binary nor in source form.
 * All redistributions of this software in source code must retain this copyright header
 * All redistributions of this software in binary form must visibly inform users about usage of this software
 *
 * development@frostnerd.com
 */
class DnsVpnService : VpnService(), Runnable {
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var handle: ProxyHandler? = null
    private var dnsProxy: SmokeProxy? = null
    private var vpnProxy: VPNTunnelProxy? = null
    private var destroyed = false
    private lateinit var notificationBuilder: NotificationCompat.Builder
    private lateinit var primaryServer: ServerConfiguration
    private var secondaryServer: ServerConfiguration? = null
    private var queryCountOffset: Long = 0

    /*
        URLs passed to the Service, which haven't been retrieved from the settings.
        Null if the current servers are from the settings
     */
    private var primaryUserServerUrl: String? = null
    private var secondaryUserServerUrl: String? = null

    companion object {
        const val BROADCAST_VPN_ACTIVE = BuildConfig.APPLICATION_ID + ".VPN_ACTIVE"
        const val BROADCAST_VPN_INACTIVE = BuildConfig.APPLICATION_ID + ".VPN_INACTIVE"
        var currentTrafficStats: TrafficStats? = null
            private set

        fun startVpn(context: Context, primaryServerUrl: String? = null, secondaryServerUrl: String? = null) {
            val intent = Intent(context, DnsVpnService::class.java)
            if (primaryServerUrl != null) intent.putExtra(
                BackgroundVpnConfigureActivity.extraKeyPrimaryUrl,
                primaryServerUrl
            )
            if (secondaryServerUrl != null) intent.putExtra(
                BackgroundVpnConfigureActivity.extraKeySecondaryUrl,
                secondaryServerUrl
            )
            context.startForegroundServiceCompat(intent)
        }

        fun restartVpn(context: Context, fetchServersFromSettings: Boolean) {
            val bundle = Bundle()
            bundle.putBoolean("fetch_servers", fetchServersFromSettings)
            sendCommand(context, Command.RESTART, bundle)
        }

        fun restartVpn(context: Context, primaryServerUrl: String?, secondaryServerUrl: String?) {
            val bundle = Bundle()
            if (primaryServerUrl != null) bundle.putString(
                BackgroundVpnConfigureActivity.extraKeyPrimaryUrl,
                primaryServerUrl
            )
            if (secondaryServerUrl != null) bundle.putString(
                BackgroundVpnConfigureActivity.extraKeySecondaryUrl,
                secondaryServerUrl
            )
            sendCommand(context, Command.RESTART, bundle)
        }

        fun sendCommand(context: Context, command: Command, extras: Bundle? = null) {
            val intent = Intent(context, DnsVpnService::class.java).putExtra("command", command)
            if (extras != null) intent.putExtras(extras)
            context.startService(intent)
        }
    }


    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            destroy()
            stopForeground(true)
            stopSelf()
            (application as SmokeScreen).customUncaughtExceptionHandler.uncaughtException(t,e)
        }
        log("Service onCreate()")

        notificationBuilder = NotificationCompat.Builder(this, Notifications.servicePersistentNotificationChannel(this))
        notificationBuilder.setContentTitle(getString(R.string.app_name))
        notificationBuilder.setSmallIcon(R.mipmap.ic_launcher_round)
        notificationBuilder.setOngoing(true)
        notificationBuilder.setAutoCancel(false)
        notificationBuilder.setSound(null)
        notificationBuilder.setUsesChronometer(true)
        notificationBuilder.setContentIntent(
            PendingIntent.getActivity(
                this, 1,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        updateNotification(0)
        log("Service created.")
    }

    private fun updateNotification(queryCount: Int? = null) {
        if (queryCount != null) notificationBuilder.setSubText(
            getString(
                R.string.notification_main_subtext,
                queryCount + queryCountOffset
            )
        )
        startForeground(1, notificationBuilder.build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("Service onStartCommand", intent = intent)
        if (intent != null && intent.hasExtra("command")) {
            val command = intent.getSerializableExtra("command") as Command

            when (command) {
                Command.STOP -> {
                    log("Received STOP command, stopping service.")
                    destroy()
                    stopForeground(true)
                    stopSelf()
                }
                Command.RESTART -> {
                    log("Received RESTART command, restarting vpn.")
                    if (intent.getBooleanExtra("fetch_servers", false)) {
                        log("Re-fetching the servers (from intent or settings)")
                        setServerConfiguration(intent)
                    }
                    setNotificationText()
                    recreateVpn()
                }
            }
        } else {
            log("No command passed, fetching servers and establishing connection if needed")
            if (!destroyed) {
                if (!this::primaryServer.isInitialized) {
                    setServerConfiguration(intent)
                    setNotificationText()
                }
                updateNotification(0)
                establishVpn()
            }
        }
        return if (destroyed) Service.START_NOT_STICKY else Service.START_STICKY
    }

    private fun setServerConfiguration(intent: Intent?) {
        log("Updating server configuration..")
        if (intent != null) {
            if (intent.hasExtra(BackgroundVpnConfigureActivity.extraKeyPrimaryUrl)) {
                primaryUserServerUrl = intent.getStringExtra(BackgroundVpnConfigureActivity.extraKeyPrimaryUrl)
                primaryServer = ServerConfiguration.createSimpleServerConfig(primaryUserServerUrl!!)
            } else {
                primaryUserServerUrl = null
                primaryServer = getPreferences().primaryServerConfig
            }

            if (intent.hasExtra(BackgroundVpnConfigureActivity.extraKeySecondaryUrl)) {
                secondaryUserServerUrl = intent.getStringExtra(BackgroundVpnConfigureActivity.extraKeySecondaryUrl)
                secondaryServer = ServerConfiguration.createSimpleServerConfig(secondaryUserServerUrl!!)
            } else {
                secondaryUserServerUrl = null
                secondaryServer = getPreferences().secondaryServerConfig
            }
        } else {
            primaryServer = getPreferences().primaryServerConfig
            secondaryServer = getPreferences().secondaryServerConfig
            primaryUserServerUrl = null
            secondaryUserServerUrl = null
        }
        log("Server configuration updated to $primaryServer and $secondaryServer")
    }

    private fun setNotificationText() {
        val text = if (secondaryServer != null) {
            getString(
                R.string.notification_main_text_with_secondary,
                primaryServer.urlCreator.baseUrl,
                secondaryServer!!.urlCreator.baseUrl,
                getPreferences().totalBypassPackageCount,
                dnsProxy?.cache?.livingCachedEntries() ?: 0
            )
        } else {
            getString(
                R.string.notification_main_text,
                primaryServer.urlCreator.baseUrl,
                getPreferences().totalBypassPackageCount,
                dnsProxy?.cache?.livingCachedEntries() ?: 0
            )
        }
        notificationBuilder.setStyle(NotificationCompat.BigTextStyle(notificationBuilder).bigText(text))
    }

    private fun establishVpn() {
        log("Establishing VPN")
        if (fileDescriptor == null) {
            fileDescriptor = createBuilder().establish()
            run()
        } else log("Connection already running, no need to establish.")
    }

    private fun recreateVpn() {
        log("Recreating the VPN (destroying & establishing)")
        destroy()
        destroyed = false
        establishVpn()
    }

    private fun destroy() {
        log("Destroying the service")
        if (!destroyed) {
            queryCountOffset += currentTrafficStats?.packetsReceivedFromDevice ?: 0
            vpnProxy?.stop()
            fileDescriptor?.close()
            vpnProxy = null
            fileDescriptor = null
            destroyed = true
        }
        currentTrafficStats = null
    }

    override fun onDestroy() {
        super.onDestroy()
        log("onDestroy() called (Was destroyed from within: $destroyed")
        if (!destroyed && resources.getBoolean(R.bool.keep_service_alive)) {
            log("The service wasn't destroyed from within and keep_service_alive is true, restarting VPN.")
            val restartIntent = Intent(this, VpnRestartService::class.java)
            if (primaryUserServerUrl != null) restartIntent.putExtra(
                BackgroundVpnConfigureActivity.extraKeyPrimaryUrl,
                primaryUserServerUrl
            )
            if (secondaryUserServerUrl != null) restartIntent.putExtra(
                BackgroundVpnConfigureActivity.extraKeySecondaryUrl,
                secondaryUserServerUrl
            )
            startForegroundServiceCompat(restartIntent)
        } else {
            destroy()
            LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(BROADCAST_VPN_INACTIVE))
        }
    }

    override fun onRevoke() {
        log("onRevoke() called")
        destroy()
        stopForeground(true)
        stopSelf()
        if (getPreferences().disallowOtherVpns) {
            println("Disallow other VPNs is true, restarting in 250ms")
            Handler(Looper.getMainLooper()).postDelayed({
                BackgroundVpnConfigureActivity.prepareVpn(this, primaryUserServerUrl, secondaryUserServerUrl)
            }, 250)
        }
    }

    private fun createBuilder(): Builder {
        log("Creating the VpnBuilder.")
        val builder = Builder()

        val dummyServerIpv4 = getPreferences().dummyDnsAddressIpv4
        val dummyServerIpv6 = getPreferences().dummyDnsAddressIpv6
        log("Dummy address for Ipv4: $dummyServerIpv4")
        log("Dummy address for Ipv6: $dummyServerIpv6")

        var couldSetAddress = false
        for (prefix in resources.getStringArray(R.array.interface_address_prefixes)) {
            try {
                builder.addAddress("$prefix.134", 24)
                couldSetAddress = true
                log("Ipv4-Address set to $prefix.134.")
                break
            } catch (ignored: IllegalArgumentException) {
                log("Couldn't set Ipv4-Address $prefix.134")
            }
        }

        if (!couldSetAddress) {
            builder.addAddress("192.168.0.10", 24)
            log("Couldn't set any dynamic address, trying 192.168.0.10...")
        }
        couldSetAddress = false

        var tries = 0
        do {
            val addr = NetworkUtil.randomLocalIPv6Address()
            try {
                builder.addAddress(addr, 48)
                couldSetAddress = true
                log("Ipv6-Address set to $addr")
                break
            } catch (e: IllegalArgumentException) {
                if(tries >= 5) throw e
                log("Couldn't set Ipv6-Address $addr, try $tries")
            }
        } while(!couldSetAddress && ++tries < 5)

        if (getPreferences().catchKnownDnsServers) {
            log("Interception of requests towards known DNS servers is enabled, adding routes.")
            for (server in DnsServerInformation.waitUntilKnownServersArePopulated(-1)!!.values) {
                log("Adding all routes for ${server.name}")
                for (ipv4Server in server.getIpv4Servers()) {
                    log("Adding route for Ipv4 ${ipv4Server.address.address}")
                    builder.addRoute(ipv4Server.address.address, 32)
                }
                for (ipv6Server in server.getIpv6Servers()) {
                    log("Adding route for Ipv6 ${ipv6Server.address.address}")
                    builder.addRoute(ipv6Server.address.address, 128)
                }
            }
        } else log("Not intercepting traffic towards known DNS servers.")
        builder.setSession(getString(R.string.app_name))
        builder.addDnsServer(dummyServerIpv4)
        builder.addDnsServer(dummyServerIpv6)
        builder.addRoute(dummyServerIpv4, 32)
        builder.addRoute(dummyServerIpv6, 128)
        builder.allowFamily(OsConstants.AF_INET)
        builder.allowFamily(OsConstants.AF_INET6)
        builder.setBlocking(true)

        log("Applying ${getPreferences().totalBypassPackageCount} disallowed packages.")
        for (defaultBypassPackage in getPreferences().bypassPackagesIterator) {
            if (isPackageInstalled(defaultBypassPackage)) { //TODO Check what is faster: catching the exception, or checking ourselves
                builder.addDisallowedApplication(defaultBypassPackage)
            } else log("Package $defaultBypassPackage not installed, thus not bypassing")
        }
        return builder
    }

    override fun run() {
        log("run() called")
        val list = mutableListOf<ServerConfiguration>()
        list.add(primaryServer)
        if (secondaryServer != null) list.add(secondaryServer!!)
        log("Using servers: $1", formatArgs = *arrayOf(list))

        log("Creating handle.")
        handle = ProxyHandler(
            list,
            connectTimeout = 500,
            queryCountCallback = {
                setNotificationText()
                updateNotification(it)
            }
        )
        log("Handle created, creating DNS proxy")
        dnsProxy = SmokeProxy(handle!!, this)
        log("DnsProxy created, creating VPN proxy")
        vpnProxy = VPNTunnelProxy(dnsProxy!!)

        log("VPN proxy creating, trying to run...")
        vpnProxy!!.run(fileDescriptor!!)
        log("VPN proxy started.")
        currentTrafficStats = vpnProxy!!.trafficStats
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(BROADCAST_VPN_ACTIVE))
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getApplicationInfo(packageName, 0).enabled
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}

fun AbstractHttpsDNSHandle.Companion.findKnownServerByUrl(url: String): HttpsDnsServerInformation? {
    for (info in AbstractHttpsDNSHandle.KNOWN_DNS_SERVERS.values) {
        for (server in info.servers) {
            if (server.address.getUrl().contains(url, true)) return info
        }
    }
    return null
}

enum class Command : Serializable {
    STOP, RESTART
}