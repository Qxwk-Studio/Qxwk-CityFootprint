package top.qxwkstudio.travel.ui

import android.view.View
import android.view.animation.AnimationUtils
import top.qxwkstudio.travel.R

/**
 * 浮层 / 整块内容出现时的「淡入 + 轻微上移」，动画本体在 res/anim/anim_fade_up ——
 * 也让 tab 切换进来的那一页用同一份（FragmentTransaction.setCustomAnimations），
 * 各处别自己再写一套时长/位移。
 *
 * 刻意只做**进入**、不做退出：退出的场合要么是直接 GONE（城市明细卡、图例收起），
 * 要么紧接着就被新内容替换（报错文案）。做淡出得监听动画结束再隐藏、还得记得把 alpha 复位，
 * 为这点观感引入一份需要维护的状态不划算。
 */
fun View.fadeIn() {
    startAnimation(AnimationUtils.loadAnimation(context, R.anim.anim_fade_up))
}