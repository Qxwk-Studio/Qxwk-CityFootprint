package top.qxwkstudio.travel.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import top.qxwkstudio.travel.databinding.FragmentPlaceholderBinding

/**
 * 占位页：某个 tab 的 2.0 实现还没到时用。
 *
 * 存在的意义是让**主壳先落地**：五个 tab 的导航、返回、深色模式、edge-to-edge 都能先按真实形态跑起来，
 * 具体页面再一个个填（地图 P4、成就 P5）。文案由调用方传，不在布局里写死 ——
 * 这样同一个类可以给两个 tab 用，也不会出现「布局里写的是地图、实际放的是成就」。
 */
class PlaceholderFragment : Fragment() {

    private var _binding: FragmentPlaceholderBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlaceholderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val resId = requireArguments().getInt(ARG_MESSAGE)
        binding.textMessage.setText(resId)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_MESSAGE = "message"

        fun newInstance(messageRes: Int): PlaceholderFragment = PlaceholderFragment().apply {
            arguments = Bundle().apply { putInt(ARG_MESSAGE, messageRes) }
        }
    }
}
