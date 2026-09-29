// 全站统计页脚本，原先内联在 stats.html 里。抽出是为了配合 CSP（script-src 'self'，内联脚本会被拦）。
// 依赖顺序：cities.js / app.js 先，本文件最后。
// 城市→省份的 findProvince 用的是 app.js 里那一份（足迹管理页共用同一口径，别在这里再抄一份）

// 拉取全站统计（api() 自动携带登录凭证，管理员能看到含私密的完整统计）。
// 走 apiWatch：命中会话级缓存就先画一版，网络回来再画一版（站点统计 1 天内的变化本来就小）。
apiWatch('/stats', data => {
    // 管理员视图提示（数据来自后端 isAdmin，始终与所见一致）
    const adminNote = document.getElementById('adminViewNote');
    if (adminNote) adminNote.style.display = data.isAdmin ? '' : 'none';

    document.getElementById('totalVisits').textContent = data.totalVisits;
    document.getElementById('totalCities').textContent = data.totalCities;
    document.getElementById('totalUsers').textContent = data.totalUsers;
    // 覆盖省份数：从城市排名去重省份
    const provs = new Set((data.cityRank || []).map(c => findProvince(c.city)).filter(Boolean));
    document.getElementById('totalProvinces').textContent = provs.size;

    const list = document.getElementById('cityRankList');
    // 一座城市都没有时排名处放空态，但**不 return**：下面那份成就骨架（42 条，人数全 0）
    // 还得照常渲染，早退会让它永远停在 HTML 里的「正在加载统计…」。
    const hasRank = data.cityRank.length > 0;
    if (hasRank) list.innerHTML = '';
    else list.innerHTML = '<div class="empty">还没有任何足迹，去添加第一座城市吧 ✈️</div>';
    const RANK_DEFAULT = 10, RANK_EXPAND = 50; // 默认前 10 名，点击展开到前 50 名
    var expanded = false;
    function drawCityRank() {
      const shown = expanded ? RANK_EXPAND : RANK_DEFAULT;
      list.innerHTML = '';
      data.cityRank.slice(0, shown).forEach((c, i) => {
        const item = document.createElement('div');
        item.className = 'rank-item';
        const province = findProvince(c.city);
        // 城市名是用户可填的内容（后端只校验非空且 ≤30 字符），拼进 innerHTML 前必须转义，
        // 否则随便一个账号用接口写条带脚本的「城市名」，就能在这个公开页面上执行脚本。
        // 省份取自本地城市表（可信），一并转义只是顺手对齐。
        item.innerHTML = `
        <span class="rank-no${i < 3 ? ' top' : ''}">${i + 1}</span>
        <span class="rank-city">${escapeHtml(c.city)}${province ? '<span class="rank-province">· ' + escapeHtml(province) + '</span>' : ''}</span>
        <span class="rank-meta">去过 ${c.people} 人</span>
        <span class="rank-count">${c.count} 次</span>`;
        list.appendChild(item);
      });
      const more = document.createElement('button');
      more.type = 'button';
      more.className = 'rank-toggle';
      if (expanded) {
        more.textContent = '收起（仅显示前 ' + RANK_DEFAULT + ' 名）';
      } else if (data.cityRank.length > RANK_DEFAULT) {
        more.textContent = '展开查看前 ' + Math.min(RANK_EXPAND, data.cityRank.length) + ' 名（共 ' + data.cityRank.length + ' 座城市）';
      } else {
        more.textContent = '共 ' + data.cityRank.length + ' 座城市';
        more.disabled = true;
      }
      more.addEventListener('click', function () { expanded = !expanded; drawCityRank(); });
      list.appendChild(more);
    }
    if (hasRank) drawCityRank();

    // 成就达成人数：由后端算好（判定逻辑只存在于 backend/src/achievements.js），
    // 连同 42 条成就的图标/名称/说明一起收到 —— 网页不再自带定义，也不再本地跑判定。
    // 这正是 /api/stats 不再回 users[].cities 的原因：只有 totalUsers 一个数字。
    const achList = document.getElementById('achievementCountList');
    achList.innerHTML = '';
    (data.achievements || []).forEach(cat => {
      const catEl = document.createElement('div');
      catEl.className = 'ach-count-cat';
      catEl.textContent = cat.title;
      achList.appendChild(catEl);
      cat.items.forEach(a => {
        const item = document.createElement('div');
        item.className = 'ach-count-item';
        item.innerHTML = `
          <span class="ach-count-icon">${a.icon}</span>
          <span class="ach-count-name">${a.name}${a.desc ? '<span class="ach-count-desc">' + a.desc + '</span>' : ''}</span>
          <span class="ach-count-num ${a.count > 0 ? 'done' : ''}">${a.count} 人</span>`;
        achList.appendChild(item);
      });
    });
  })
  .catch(() => {
    // 失败时四张统计卡与两个列表都要收尾：原先只把城市排名换成错误文案，
    // 其余三处会永远停在「加载中…」/「正在加载统计…」，看着像卡住
    ['totalVisits', 'totalCities', 'totalProvinces', 'totalUsers'].forEach(id => {
      document.getElementById(id).textContent = '—';
    });
    document.getElementById('cityRankList').innerHTML = '<div class="empty">统计加载失败，请稍后重试</div>';
    document.getElementById('achievementCountList').innerHTML = '<div class="empty">统计加载失败，请稍后重试</div>';
  });
