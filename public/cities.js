// 国内地级市数据（市级单位，含经纬度）
// 结构：[{ name: '市名', province: '省份', lat: 纬度, lng: 经度 }, ...]
// 如需补充城市，按此格式往对应省份数组里加即可。
// 坐标取城市主城区近似值，打点用足够。

const CITIES = [
  // ── 直辖市 ──
  { name: '北京', province: '北京', lat: 39.904, lng: 116.407 },
  { name: '上海', province: '上海', lat: 31.230, lng: 121.474 },
  { name: '天津', province: '天津', lat: 39.084, lng: 117.201 },
  { name: '重庆', province: '重庆', lat: 29.563, lng: 106.551 },

  // ── 河北 ──
  { name: '石家庄', province: '河北', lat: 38.043, lng: 114.515 },
  { name: '唐山', province: '河北', lat: 39.631, lng: 118.180 },
  { name: '秦皇岛', province: '河北', lat: 39.935, lng: 119.600 },
  { name: '邯郸', province: '河北', lat: 36.626, lng: 114.539 },
  { name: '邢台', province: '河北', lat: 37.071, lng: 114.504 },
  { name: '保定', province: '河北', lat: 38.874, lng: 115.465 },
  { name: '张家口', province: '河北', lat: 40.824, lng: 114.886 },
  { name: '承德', province: '河北', lat: 40.951, lng: 117.963 },
  { name: '沧州', province: '河北', lat: 38.304, lng: 116.839 },
  { name: '廊坊', province: '河北', lat: 39.538, lng: 116.684 },
  { name: '衡水', province: '河北', lat: 37.739, lng: 115.671 },

  // ── 山西 ──
  { name: '太原', province: '山西', lat: 37.870, lng: 112.549 },
  { name: '大同', province: '山西', lat: 40.076, lng: 113.300 },
  { name: '阳泉', province: '山西', lat: 37.857, lng: 113.580 },
  { name: '长治', province: '山西', lat: 36.196, lng: 113.116 },
  { name: '晋城', province: '山西', lat: 35.490, lng: 112.851 },
  { name: '朔州', province: '山西', lat: 39.332, lng: 112.433 },
  { name: '晋中', province: '山西', lat: 37.687, lng: 112.753 },
  { name: '运城', province: '山西', lat: 35.026, lng: 111.007 },
  { name: '忻州', province: '山西', lat: 38.417, lng: 112.734 },
  { name: '临汾', province: '山西', lat: 36.088, lng: 111.519 },
  { name: '吕梁', province: '山西', lat: 37.518, lng: 111.143 },

  // ── 内蒙古 ──
  { name: '呼和浩特', province: '内蒙古', lat: 40.842, lng: 111.750 },
  { name: '包头', province: '内蒙古', lat: 40.657, lng: 109.840 },
  { name: '乌海', province: '内蒙古', lat: 39.655, lng: 106.794 },
  { name: '赤峰', province: '内蒙古', lat: 42.257, lng: 118.888 },
  { name: '通辽', province: '内蒙古', lat: 43.652, lng: 122.243 },
  { name: '鄂尔多斯', province: '内蒙古', lat: 39.608, lng: 109.781 },
  { name: '呼伦贝尔', province: '内蒙古', lat: 49.212, lng: 119.766 },
  { name: '巴彦淖尔', province: '内蒙古', lat: 40.743, lng: 107.388 },
  { name: '乌兰察布', province: '内蒙古', lat: 40.994, lng: 113.132 },

  // ── 辽宁 ──
  { name: '沈阳', province: '辽宁', lat: 41.806, lng: 123.432 },
  { name: '大连', province: '辽宁', lat: 38.914, lng: 121.615 },
  { name: '鞍山', province: '辽宁', lat: 41.108, lng: 122.994 },
  { name: '抚顺', province: '辽宁', lat: 41.880, lng: 123.957 },
  { name: '本溪', province: '辽宁', lat: 41.294, lng: 123.765 },
  { name: '丹东', province: '辽宁', lat: 40.129, lng: 124.383 },
  { name: '锦州', province: '辽宁', lat: 41.096, lng: 121.127 },
  { name: '营口', province: '辽宁', lat: 40.667, lng: 122.235 },
  { name: '阜新', province: '辽宁', lat: 42.022, lng: 121.670 },
  { name: '辽阳', province: '辽宁', lat: 41.270, lng: 123.237 },
  { name: '盘锦', province: '辽宁', lat: 41.124, lng: 122.071 },
  { name: '铁岭', province: '辽宁', lat: 42.286, lng: 123.726 },
  { name: '朝阳', province: '辽宁', lat: 41.573, lng: 120.451 },
  { name: '葫芦岛', province: '辽宁', lat: 40.711, lng: 120.837 },

  // ── 吉林 ──
  { name: '长春', province: '吉林', lat: 43.817, lng: 125.324 },
  { name: '吉林', province: '吉林', lat: 43.838, lng: 126.550 },
  { name: '四平', province: '吉林', lat: 43.167, lng: 124.351 },
  { name: '辽源', province: '吉林', lat: 42.888, lng: 125.144 },
  { name: '通化', province: '吉林', lat: 41.728, lng: 125.940 },
  { name: '白山', province: '吉林', lat: 41.943, lng: 126.424 },
  { name: '松原', province: '吉林', lat: 45.141, lng: 124.825 },
  { name: '白城', province: '吉林', lat: 45.620, lng: 122.840 },
  { name: '延边', province: '吉林', lat: 42.904, lng: 129.508 }, // 延吉

  // ── 黑龙江 ──
  { name: '哈尔滨', province: '黑龙江', lat: 45.803, lng: 126.535 },
  { name: '齐齐哈尔', province: '黑龙江', lat: 47.354, lng: 123.918 },
  { name: '鸡西', province: '黑龙江', lat: 45.295, lng: 130.969 },
  { name: '鹤岗', province: '黑龙江', lat: 47.350, lng: 130.298 },
  { name: '双鸭山', province: '黑龙江', lat: 46.646, lng: 131.159 },
  { name: '大庆', province: '黑龙江', lat: 46.589, lng: 125.103 },
  { name: '伊春', province: '黑龙江', lat: 47.727, lng: 128.839 },
  { name: '佳木斯', province: '黑龙江', lat: 46.800, lng: 130.319 },
  { name: '七台河', province: '黑龙江', lat: 45.771, lng: 131.003 },
  { name: '牡丹江', province: '黑龙江', lat: 44.551, lng: 129.633 },
  { name: '黑河', province: '黑龙江', lat: 50.245, lng: 127.529 },
  { name: '绥化', province: '黑龙江', lat: 46.653, lng: 126.990 },

  // ── 江苏 ──
  { name: '南京', province: '江苏', lat: 32.060, lng: 118.797 },
  { name: '无锡', province: '江苏', lat: 31.491, lng: 120.312 },
  { name: '徐州', province: '江苏', lat: 34.205, lng: 117.285 },
  { name: '常州', province: '江苏', lat: 31.811, lng: 119.974 },
  { name: '苏州', province: '江苏', lat: 31.299, lng: 120.585 },
  { name: '南通', province: '江苏', lat: 31.980, lng: 120.894 },
  { name: '连云港', province: '江苏', lat: 34.600, lng: 119.222 },
  { name: '淮安', province: '江苏', lat: 33.611, lng: 119.015 },
  { name: '盐城', province: '江苏', lat: 33.348, lng: 120.163 },
  { name: '扬州', province: '江苏', lat: 32.394, lng: 119.413 },
  { name: '镇江', province: '江苏', lat: 32.188, lng: 119.425 },
  { name: '泰州', province: '江苏', lat: 32.456, lng: 119.923 },
  { name: '宿迁', province: '江苏', lat: 33.963, lng: 118.275 },

  // ── 浙江 ──
  { name: '杭州', province: '浙江', lat: 30.274, lng: 120.155 },
  { name: '宁波', province: '浙江', lat: 29.868, lng: 121.544 },
  { name: '温州', province: '浙江', lat: 27.994, lng: 120.699 },
  { name: '嘉兴', province: '浙江', lat: 30.746, lng: 120.755 },
  { name: '湖州', province: '浙江', lat: 30.893, lng: 120.087 },
  { name: '绍兴', province: '浙江', lat: 30.030, lng: 120.580 },
  { name: '金华', province: '浙江', lat: 29.079, lng: 119.647 },
  { name: '衢州', province: '浙江', lat: 28.936, lng: 118.874 },
  { name: '舟山', province: '浙江', lat: 30.000, lng: 122.207 },
  { name: '台州', province: '浙江', lat: 28.656, lng: 121.421 },
  { name: '丽水', province: '浙江', lat: 28.452, lng: 119.922 },

  // ── 安徽 ──
  { name: '合肥', province: '安徽', lat: 31.821, lng: 117.227 },
  { name: '芜湖', province: '安徽', lat: 31.352, lng: 118.433 },
  { name: '蚌埠', province: '安徽', lat: 32.916, lng: 117.389 },
  { name: '淮南', province: '安徽', lat: 32.626, lng: 117.018 },
  { name: '马鞍山', province: '安徽', lat: 31.671, lng: 118.507 },
  { name: '淮北', province: '安徽', lat: 33.955, lng: 116.799 },
  { name: '铜陵', province: '安徽', lat: 30.945, lng: 117.812 },
  { name: '安庆', province: '安徽', lat: 30.543, lng: 117.063 },
  { name: '黄山', province: '安徽', lat: 29.715, lng: 118.338 },
  { name: '滁州', province: '安徽', lat: 32.302, lng: 118.317 },
  { name: '阜阳', province: '安徽', lat: 32.890, lng: 115.814 },
  { name: '宿州', province: '安徽', lat: 33.647, lng: 116.964 },
  { name: '六安', province: '安徽', lat: 31.755, lng: 116.520 },
  { name: '亳州', province: '安徽', lat: 33.846, lng: 115.779 },
  { name: '池州', province: '安徽', lat: 30.657, lng: 117.491 },
  { name: '宣城', province: '安徽', lat: 30.941, lng: 118.759 },

  // ── 福建 ──
  { name: '福州', province: '福建', lat: 26.074, lng: 119.297 },
  { name: '厦门', province: '福建', lat: 24.480, lng: 118.089 },
  { name: '莆田', province: '福建', lat: 25.454, lng: 119.008 },
  { name: '三明', province: '福建', lat: 26.264, lng: 117.639 },
  { name: '泉州', province: '福建', lat: 24.874, lng: 118.676 },
  { name: '漳州', province: '福建', lat: 24.514, lng: 117.647 },
  { name: '南平', province: '福建', lat: 26.642, lng: 118.178 },
  { name: '龙岩', province: '福建', lat: 25.075, lng: 117.017 },
  { name: '宁德', province: '福建', lat: 26.666, lng: 119.548 },

  // ── 江西 ──
  { name: '南昌', province: '江西', lat: 28.682, lng: 115.858 },
  { name: '景德镇', province: '江西', lat: 29.269, lng: 117.178 },
  { name: '萍乡', province: '江西', lat: 27.622, lng: 113.854 },
  { name: '九江', province: '江西', lat: 29.705, lng: 116.002 },
  { name: '新余', province: '江西', lat: 27.818, lng: 114.917 },
  { name: '鹰潭', province: '江西', lat: 28.260, lng: 117.070 },
  { name: '赣州', province: '江西', lat: 25.831, lng: 114.935 },
  { name: '吉安', province: '江西', lat: 27.114, lng: 114.993 },
  { name: '宜春', province: '江西', lat: 27.815, lng: 114.416 },
  { name: '抚州', province: '江西', lat: 27.954, lng: 116.358 },
  { name: '上饶', province: '江西', lat: 28.455, lng: 117.943 },

  // ── 山东 ──
  { name: '济南', province: '山东', lat: 36.651, lng: 117.120 },
  { name: '青岛', province: '山东', lat: 36.067, lng: 120.383 },
  { name: '淄博', province: '山东', lat: 36.813, lng: 118.055 },
  { name: '枣庄', province: '山东', lat: 34.810, lng: 117.323 },
  { name: '东营', province: '山东', lat: 37.435, lng: 118.675 },
  { name: '烟台', province: '山东', lat: 37.464, lng: 121.448 },
  { name: '潍坊', province: '山东', lat: 36.707, lng: 119.162 },
  { name: '济宁', province: '山东', lat: 35.415, lng: 116.587 },
  { name: '泰安', province: '山东', lat: 36.200, lng: 117.088 },
  { name: '威海', province: '山东', lat: 37.513, lng: 122.121 },
  { name: '日照', province: '山东', lat: 35.416, lng: 119.527 },
  { name: '临沂', province: '山东', lat: 35.104, lng: 118.356 },
  { name: '德州', province: '山东', lat: 37.436, lng: 116.357 },
  { name: '聊城', province: '山东', lat: 36.457, lng: 115.985 },
  { name: '滨州', province: '山东', lat: 37.382, lng: 117.971 },
  { name: '菏泽', province: '山东', lat: 35.233, lng: 115.481 },

  // ── 河南 ──
  { name: '郑州', province: '河南', lat: 34.747, lng: 113.625 },
  { name: '开封', province: '河南', lat: 34.798, lng: 114.308 },
  { name: '洛阳', province: '河南', lat: 34.620, lng: 112.454 },
  { name: '平顶山', province: '河南', lat: 33.766, lng: 113.193 },
  { name: '安阳', province: '河南', lat: 36.099, lng: 114.393 },
  { name: '鹤壁', province: '河南', lat: 35.748, lng: 114.297 },
  { name: '新乡', province: '河南', lat: 35.303, lng: 113.926 },
  { name: '焦作', province: '河南', lat: 35.216, lng: 113.242 },
  { name: '濮阳', province: '河南', lat: 35.762, lng: 115.029 },
  { name: '许昌', province: '河南', lat: 34.036, lng: 113.852 },
  { name: '漯河', province: '河南', lat: 33.581, lng: 114.017 },
  { name: '三门峡', province: '河南', lat: 34.773, lng: 111.200 },
  { name: '南阳', province: '河南', lat: 32.991, lng: 112.529 },
  { name: '商丘', province: '河南', lat: 34.414, lng: 115.656 },
  { name: '信阳', province: '河南', lat: 32.147, lng: 114.091 },
  { name: '周口', province: '河南', lat: 33.626, lng: 114.697 },
  { name: '驻马店', province: '河南', lat: 33.014, lng: 114.022 },

  // ── 湖北 ──
  { name: '武汉', province: '湖北', lat: 30.593, lng: 114.305 },
  { name: '黄石', province: '湖北', lat: 30.200, lng: 115.039 },
  { name: '十堰', province: '湖北', lat: 32.629, lng: 110.798 },
  { name: '宜昌', province: '湖北', lat: 30.692, lng: 111.287 },
  { name: '襄阳', province: '湖北', lat: 32.008, lng: 112.122 },
  { name: '鄂州', province: '湖北', lat: 30.391, lng: 114.895 },
  { name: '荆门', province: '湖北', lat: 31.035, lng: 112.199 },
  { name: '孝感', province: '湖北', lat: 30.925, lng: 113.917 },
  { name: '荆州', province: '湖北', lat: 30.335, lng: 112.240 },
  { name: '黄冈', province: '湖北', lat: 30.447, lng: 114.872 },
  { name: '咸宁', province: '湖北', lat: 29.841, lng: 114.323 },
  { name: '随州', province: '湖北', lat: 31.690, lng: 113.383 },

  // ── 湖南 ──
  { name: '长沙', province: '湖南', lat: 28.228, lng: 112.939 },
  { name: '株洲', province: '湖南', lat: 27.827, lng: 113.134 },
  { name: '湘潭', province: '湖南', lat: 27.829, lng: 112.944 },
  { name: '衡阳', province: '湖南', lat: 26.893, lng: 112.572 },
  { name: '邵阳', province: '湖南', lat: 27.239, lng: 111.468 },
  { name: '岳阳', province: '湖南', lat: 29.357, lng: 113.129 },
  { name: '常德', province: '湖南', lat: 29.032, lng: 111.699 },
  { name: '张家界', province: '湖南', lat: 29.117, lng: 110.479 },
  { name: '益阳', province: '湖南', lat: 28.554, lng: 112.355 },
  { name: '郴州', province: '湖南', lat: 25.770, lng: 113.015 },
  { name: '永州', province: '湖南', lat: 26.420, lng: 111.613 },
  { name: '怀化', province: '湖南', lat: 27.550, lng: 110.002 },
  { name: '娄底', province: '湖南', lat: 27.728, lng: 111.996 },

  // ── 广东 ──
  { name: '广州', province: '广东', lat: 23.129, lng: 113.264 },
  { name: '韶关', province: '广东', lat: 24.810, lng: 113.597 },
  { name: '深圳', province: '广东', lat: 22.543, lng: 114.058 },
  { name: '珠海', province: '广东', lat: 22.271, lng: 113.577 },
  { name: '汕头', province: '广东', lat: 23.354, lng: 116.682 },
  { name: '佛山', province: '广东', lat: 23.022, lng: 113.122 },
  { name: '江门', province: '广东', lat: 22.579, lng: 113.082 },
  { name: '湛江', province: '广东', lat: 21.271, lng: 110.359 },
  { name: '茂名', province: '广东', lat: 21.663, lng: 110.925 },
  { name: '肇庆', province: '广东', lat: 23.047, lng: 112.473 },
  { name: '惠州', province: '广东', lat: 23.110, lng: 114.416 },
  { name: '梅州', province: '广东', lat: 24.289, lng: 116.117 },
  { name: '汕尾', province: '广东', lat: 22.786, lng: 115.375 },
  { name: '河源', province: '广东', lat: 23.744, lng: 114.700 },
  { name: '阳江', province: '广东', lat: 21.858, lng: 111.982 },
  { name: '清远', province: '广东', lat: 23.682, lng: 113.056 },
  { name: '东莞', province: '广东', lat: 23.020, lng: 113.752 },
  { name: '中山', province: '广东', lat: 22.517, lng: 113.393 },
  { name: '潮州', province: '广东', lat: 23.657, lng: 116.622 },
  { name: '揭阳', province: '广东', lat: 23.550, lng: 116.373 },
  { name: '云浮', province: '广东', lat: 22.915, lng: 112.045 },

  // ── 广西 ──
  { name: '南宁', province: '广西', lat: 22.817, lng: 108.366 },
  { name: '柳州', province: '广西', lat: 24.326, lng: 109.428 },
  { name: '桂林', province: '广西', lat: 25.274, lng: 110.290 },
  { name: '梧州', province: '广西', lat: 23.477, lng: 111.279 },
  { name: '北海', province: '广西', lat: 21.481, lng: 109.120 },
  { name: '防城港', province: '广西', lat: 21.687, lng: 108.354 },
  { name: '钦州', province: '广西', lat: 21.980, lng: 108.654 },
  { name: '贵港', province: '广西', lat: 23.111, lng: 109.598 },
  { name: '玉林', province: '广西', lat: 22.654, lng: 110.181 },
  { name: '百色', province: '广西', lat: 23.902, lng: 106.618 },
  { name: '贺州', province: '广西', lat: 24.403, lng: 111.567 },
  { name: '河池', province: '广西', lat: 24.693, lng: 108.085 },
  { name: '来宾', province: '广西', lat: 23.750, lng: 109.222 },
  { name: '崇左', province: '广西', lat: 22.377, lng: 107.365 },

  // ── 海南 ──
  { name: '海口', province: '海南', lat: 20.044, lng: 110.199 },
  { name: '三亚', province: '海南', lat: 18.253, lng: 109.512 },
  { name: '三沙', province: '海南', lat: 16.831, lng: 112.339 },
  { name: '儋州', province: '海南', lat: 19.521, lng: 109.581 },

  // ── 四川 ──
  { name: '成都', province: '四川', lat: 30.573, lng: 104.067 },
  { name: '自贡', province: '四川', lat: 29.339, lng: 104.778 },
  { name: '攀枝花', province: '四川', lat: 26.582, lng: 101.719 },
  { name: '泸州', province: '四川', lat: 28.872, lng: 105.443 },
  { name: '德阳', province: '四川', lat: 31.127, lng: 104.398 },
  { name: '绵阳', province: '四川', lat: 31.467, lng: 104.680 },
  { name: '广元', province: '四川', lat: 32.436, lng: 105.844 },
  { name: '遂宁', province: '四川', lat: 30.513, lng: 105.593 },
  { name: '内江', province: '四川', lat: 29.580, lng: 105.059 },
  { name: '乐山', province: '四川', lat: 29.552, lng: 103.766 },
  { name: '南充', province: '四川', lat: 30.837, lng: 106.111 },
  { name: '眉山', province: '四川', lat: 30.049, lng: 103.832 },
  { name: '宜宾', province: '四川', lat: 28.752, lng: 104.643 },
  { name: '广安', province: '四川', lat: 30.456, lng: 106.633 },
  { name: '达州', province: '四川', lat: 31.209, lng: 107.468 },
  { name: '雅安', province: '四川', lat: 30.011, lng: 103.042 },
  { name: '巴中', province: '四川', lat: 31.867, lng: 106.753 },
  { name: '资阳', province: '四川', lat: 30.122, lng: 104.628 },
  { name: '西昌', province: '四川', lat: 27.894, lng: 102.264 }, // 凉山州府
  { name: '康定', province: '四川', lat: 29.998, lng: 101.958 }, // 甘孜州府

  // ── 贵州 ──
  { name: '贵阳', province: '贵州', lat: 26.647, lng: 106.630 },
  { name: '六盘水', province: '贵州', lat: 26.593, lng: 104.831 },
  { name: '遵义', province: '贵州', lat: 27.725, lng: 106.927 },
  { name: '安顺', province: '贵州', lat: 26.245, lng: 105.943 },
  { name: '毕节', province: '贵州', lat: 27.302, lng: 105.305 },
  { name: '铜仁', province: '贵州', lat: 27.718, lng: 109.190 },

  // ── 云南 ──
  { name: '昆明', province: '云南', lat: 25.038, lng: 102.718 },
  { name: '曲靖', province: '云南', lat: 25.490, lng: 103.796 },
  { name: '玉溪', province: '云南', lat: 24.355, lng: 102.547 },
  { name: '保山', province: '云南', lat: 25.111, lng: 99.162 },
  { name: '昭通', province: '云南', lat: 27.338, lng: 103.717 },
  { name: '丽江', province: '云南', lat: 26.872, lng: 100.230 },
  { name: '普洱', province: '云南', lat: 22.825, lng: 100.966 },
  { name: '临沧', province: '云南', lat: 23.878, lng: 100.088 },
  { name: '大理', province: '云南', lat: 25.606, lng: 100.268 },
  { name: '楚雄', province: '云南', lat: 25.032, lng: 101.546 },
  { name: '红河', province: '云南', lat: 23.367, lng: 103.375 }, // 蒙自
  { name: '文山', province: '云南', lat: 23.369, lng: 104.244 },
  { name: '西双版纳', province: '云南', lat: 22.009, lng: 100.797 }, // 景洪

  // ── 西藏 ──
  { name: '拉萨', province: '西藏', lat: 29.646, lng: 91.140 },
  { name: '日喀则', province: '西藏', lat: 29.267, lng: 88.880 },

  // ── 陕西 ──
  { name: '西安', province: '陕西', lat: 34.342, lng: 108.940 },
  { name: '铜川', province: '陕西', lat: 34.897, lng: 108.945 },
  { name: '宝鸡', province: '陕西', lat: 34.362, lng: 107.237 },
  { name: '咸阳', province: '陕西', lat: 34.329, lng: 108.709 },
  { name: '渭南', province: '陕西', lat: 34.500, lng: 109.510 },
  { name: '延安', province: '陕西', lat: 36.585, lng: 109.490 },
  { name: '汉中', province: '陕西', lat: 33.068, lng: 107.024 },
  { name: '榆林', province: '陕西', lat: 38.285, lng: 109.734 },
  { name: '安康', province: '陕西', lat: 32.685, lng: 109.029 },
  { name: '商洛', province: '陕西', lat: 33.870, lng: 109.940 },

  // ── 甘肃 ──
  { name: '兰州', province: '甘肃', lat: 36.061, lng: 103.834 },
  { name: '嘉峪关', province: '甘肃', lat: 39.773, lng: 98.290 },
  { name: '金昌', province: '甘肃', lat: 38.520, lng: 102.188 },
  { name: '白银', province: '甘肃', lat: 36.545, lng: 104.139 },
  { name: '天水', province: '甘肃', lat: 34.581, lng: 105.725 },
  { name: '武威', province: '甘肃', lat: 37.928, lng: 102.638 },
  { name: '张掖', province: '甘肃', lat: 38.926, lng: 100.450 },
  { name: '平凉', province: '甘肃', lat: 35.543, lng: 106.665 },
  { name: '酒泉', province: '甘肃', lat: 39.732, lng: 98.494 },
  { name: '庆阳', province: '甘肃', lat: 35.710, lng: 107.644 },
  { name: '定西', province: '甘肃', lat: 35.581, lng: 104.626 },
  { name: '陇南', province: '甘肃', lat: 33.401, lng: 104.922 },
  { name: '临夏', province: '甘肃', lat: 35.601, lng: 103.216 },
  { name: '甘南', province: '甘肃', lat: 34.992, lng: 102.911 }, // 合作

  // ── 青海 ──
  { name: '西宁', province: '青海', lat: 36.617, lng: 101.778 },
  { name: '海东', province: '青海', lat: 36.502, lng: 102.104 },
  { name: '格尔木', province: '青海', lat: 36.402, lng: 94.903 },

  // ── 宁夏 ──
  { name: '银川', province: '宁夏', lat: 38.487, lng: 106.231 },
  { name: '石嘴山', province: '宁夏', lat: 38.984, lng: 106.384 },
  { name: '吴忠', province: '宁夏', lat: 37.998, lng: 106.199 },
  { name: '固原', province: '宁夏', lat: 36.016, lng: 106.242 },
  { name: '中卫', province: '宁夏', lat: 37.500, lng: 105.196 },

  // ── 新疆 ──
  { name: '乌鲁木齐', province: '新疆', lat: 43.826, lng: 87.617 },
  { name: '克拉玛依', province: '新疆', lat: 45.580, lng: 84.889 },
  { name: '吐鲁番', province: '新疆', lat: 42.951, lng: 89.190 },
  { name: '哈密', province: '新疆', lat: 42.833, lng: 93.516 },
  { name: '伊宁', province: '新疆', lat: 43.914, lng: 81.277 }, // 伊犁州府
  { name: '喀什', province: '新疆', lat: 39.470, lng: 75.990 },
  { name: '库尔勒', province: '新疆', lat: 41.724, lng: 86.146 }, // 巴音郭楞州府
  { name: '阿克苏', province: '新疆', lat: 41.168, lng: 80.260 },
  { name: '和田', province: '新疆', lat: 37.114, lng: 79.923 },
  { name: '石河子', province: '新疆', lat: 44.306, lng: 86.080 },
];

// 供前端使用
if (typeof window !== 'undefined') {
  window.CITIES = CITIES;
}
