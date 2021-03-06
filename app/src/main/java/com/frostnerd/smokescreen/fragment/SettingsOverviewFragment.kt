package com.frostnerd.smokescreen.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.frostnerd.smokescreen.R
import com.frostnerd.smokescreen.activity.SettingsActivity
import com.frostnerd.smokescreen.util.DeepActionState
import kotlinx.android.synthetic.main.fragment_settings_overview.*

/*
 * Copyright (C) 2019 Daniel Wolf (Ch4t4r)
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * You can contact the developer at daniel.wolf@frostnerd.com.
 */
class SettingsOverviewFragment: Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_settings_overview, container, false)
    }

    override fun setArguments(args: Bundle?) {
        super.setArguments(args)
        processArguments(args)
    }

    private fun processArguments(args: Bundle?) {
        args?.getSerializable("deep_action")?.let {
            it as? DeepActionState
        }?.apply {
            if(this == DeepActionState.DNSSERVERMODE_SETTINGS) {
                nonVpnMode?.performClick()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        general.setOnClickListener {
            SettingsActivity.showCategory(requireContext(), SettingsActivity.Category.GENERAL)
        }
        notification.setOnClickListener {
            SettingsActivity.showCategory(requireContext(), SettingsActivity.Category.NOTIFICATION)
        }
        pin.setOnClickListener {
            SettingsActivity.showCategory(requireContext(), SettingsActivity.Category.PIN)
        }
        cache.setOnClickListener {
            SettingsActivity.showCategory(requireContext(), SettingsActivity.Category.CACHE)
        }
        logging.setOnClickListener {
            SettingsActivity.showCategory(requireContext(), SettingsActivity.Category.LOGGING)
        }
        ip.setOnClickListener {
            SettingsActivity.showCategory(requireContext(), SettingsActivity.Category.IP)
        }
        network.setOnClickListener {
            SettingsActivity.showCategory(requireContext(), SettingsActivity.Category.NETWORK)
        }
        queryLogging.setOnClickListener {
            SettingsActivity.showCategory(requireContext(), SettingsActivity.Category.QUERIES)
        }
        nonVpnMode.setOnClickListener {
            SettingsActivity.showCategory(requireContext(), SettingsActivity.Category.SERVER_MODE)
        }
        processArguments(arguments)
    }
}