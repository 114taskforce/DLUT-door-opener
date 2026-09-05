# R8 规则:Compose / OkHttp / kotlinx-coroutines 均随包自带 consumer keep 规则,
# 应用代码无反射入口(MainViewModel 经 ViewModelProvider 由自带规则覆盖),故此处无需额外规则。
# 若未来引入反射/Gson 等再按需补充。
