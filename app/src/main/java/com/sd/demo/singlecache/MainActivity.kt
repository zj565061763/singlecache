package com.sd.demo.singlecache

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sd.demo.singlecache.databinding.ActivityMainBinding
import com.sd.lib.singlecache.singleCache
import kotlinx.coroutines.launch

open class MainActivity : AppCompatActivity() {
  private val _binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
  private val _cache = singleCache { CounterModel() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(_binding.root)

    _binding.btnIncrement.setOnClickListener {
      lifecycleScope.launch {
        _cache.update { it.copy(count = it.count + 1) }
      }
    }

    _binding.btnRemove.setOnClickListener {
      lifecycleScope.launch {
        // 返回null，会删除缓存，还原为默认缓存
        _cache.update { null }
      }
    }

    _binding.btnOtherProcess.setOnClickListener {
      startActivity(Intent(this, SampleOtherProcess::class.java))
    }

    lifecycleScope.launch {
      _cache.flow.collect { data ->
        logMsg { "${javaClass.simpleName} collect $data" }
        _binding.tvContent.text = data.toString()
      }
    }
  }
}

/** 运行在`:other`进程，和[MainActivity]操作同一个缓存 */
class SampleOtherProcess : MainActivity()
