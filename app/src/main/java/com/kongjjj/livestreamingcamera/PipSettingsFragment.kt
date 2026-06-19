package com.kongjjj.livestreamingcamera

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ToggleButton
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels

class PipSettingsFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_pip_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val pipEnableSwitch = view.findViewById<SwitchCompat>(R.id.pipEnableSwitch)
        val pipPositionGrid = view.findViewById<GridLayout>(R.id.pipPositionGrid)
        val pipPaddingSeekBar = view.findViewById<SeekBar>(R.id.pipPaddingSeekBar)
        val pipPaddingValue = view.findViewById<TextView>(R.id.pipPaddingValue)
        val pipRoundedSwitch = view.findViewById<SwitchCompat>(R.id.pipRoundedSwitch)

        // Initialize state
        viewModel.isPipEnabled.observe(viewLifecycleOwner) { isEnabled ->
            if (pipEnableSwitch.isChecked != isEnabled) {
                pipEnableSwitch.isChecked = isEnabled
            }
        }

        viewModel.pipRounded.observe(viewLifecycleOwner) { isRounded ->
            if (pipRoundedSwitch.isChecked != isRounded) {
                pipRoundedSwitch.isChecked = isRounded
            }
        }

        viewModel.pipPadding.observe(viewLifecycleOwner) { padding ->
            val progress = padding / 10
            if (pipPaddingSeekBar.progress != progress) {
                pipPaddingSeekBar.progress = progress
            }
            pipPaddingValue.text = padding.toString()
        }

        viewModel.pipPosition.observe(viewLifecycleOwner) { currentPos ->
            for (i in 0 until pipPositionGrid.childCount) {
                val child = pipPositionGrid.getChildAt(i) as ToggleButton
                child.isChecked = (i == currentPos)
            }
        }

        // Listeners
        pipPaddingSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val padding = progress * 10
                    pipPaddingValue.text = padding.toString()
                    viewModel.setPipPadding(padding)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        pipRoundedSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setPipRounded(isChecked)
        }

        for (i in 0 until pipPositionGrid.childCount) {
            val child = pipPositionGrid.getChildAt(i) as ToggleButton
            child.setOnClickListener {
                viewModel.setPipPosition(i)
            }
        }

        pipEnableSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setPipEnabled(isChecked)
        }
    }
}
