package com.example.comexamplet9keyboard

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnEnable: View
    private lateinit var btnSelect: View
    private lateinit var statusEnable: TextView
    private lateinit var statusSelect: TextView
    private lateinit var statusDict: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.main_root)
        val initialPaddingLeft = root.paddingLeft
        val initialPaddingTop = root.paddingTop
        val initialPaddingRight = root.paddingRight
        val initialPaddingBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                initialPaddingLeft + maxOf(systemBars.left, ime.left),
                initialPaddingTop + systemBars.top,
                initialPaddingRight + maxOf(systemBars.right, ime.right),
                initialPaddingBottom + maxOf(systemBars.bottom, ime.bottom)
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)

        btnEnable = findViewById(R.id.btn_enable)
        btnSelect = findViewById(R.id.btn_select)
        statusEnable = findViewById(R.id.status_enable)
        statusSelect = findViewById(R.id.status_select)
        statusDict = findViewById(R.id.status_dictionary)

        btnEnable.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        btnSelect.setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        findViewById<View>(R.id.btn_dictionary).setOnClickListener {
            startActivity(Intent(this, UserDictionaryActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        checkKeyboardStatus()
    }

    private fun checkKeyboardStatus() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabledMethods = imm.enabledInputMethodList
        val isEnabled = enabledMethods.any { it.packageName == packageName }

        val currentDefault = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val isDefault = currentDefault?.contains(packageName) == true

        statusEnable.text = getString(if (isEnabled) R.string.keyboard_enabled else R.string.enable_keyboard)
        btnEnable.setBackgroundResource(if (isEnabled) R.drawable.bg_setup_button_done else R.drawable.bg_setup_button)

        statusSelect.text = getString(if (isDefault) R.string.keyboard_selected else R.string.select_keyboard)
        btnSelect.setBackgroundResource(if (isDefault) R.drawable.bg_setup_button_done else R.drawable.bg_setup_button)

        val dictCount = UserDictionaryStore.load(this).size
        statusDict.text = getString(R.string.dictionary_count, dictCount)
    }
}

