-- plates.lua — таблица NP (метки 777013.5 / 777014.5; имя НЕ «P»: в lua-setup.txt есть функция P(), её нельзя затирать): полосы здоровья над врагами (nameplates) на экране.
-- NP[3] время (GetTime, тикает — по нему JS находит таблицу), NP[4] число полос на экране, NP[5] есть ли цель (1/0).
-- NP[46] счётчик ошибок Lua этого модуля (JS пишет его рост в журнал, категория lua).
-- Дальше 8 слотов по 5 ячеек, начиная с NP[6]: fx (центр полосы, доля ширины экрана 0..1), fy (доля высоты снизу),
-- флаги = (это цель ? 1 : 0) + 2 * реакция (0 неизвестно, 1 дружественный, 2 нейтральный, 3 враждебный), уровень (-1 неизвестен),
-- хеш названия (как nameHash в Java/JS). Порядок: цель первой, затем ближние к центру экрана. Пустой слот — нули.
-- Полоса — безымянный видимый дочерний кадр WorldFrame; цель имеет прозрачность 1 (остальные около 0.5) и совпадает с UnitName("target").
NP = {777013.5, 777014.5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}

do
  local SLOTS = 8
  local acc = 0

  local function nameHash(s)
    local h = 0
    for i = 1, #s do h = (h * 31 + s:byte(i)) % 2147483647 end
    return h
  end

  local function info(f)
    local name, level = nil, -1
    for _, r in ipairs({f:GetRegions()}) do
      if r:GetObjectType() == "FontString" then
        local t = r:GetText()
        if t and t ~= "" then
          local n = tonumber(t)
          if n then
            if level < 0 then level = n end
          elseif not name then
            name = t
          end
        end
      end
    end
    return name, level
  end

  local function reaction(f)
    local hb = f:GetChildren()
    if hb and hb.GetStatusBarColor then
      local r, g, b = hb:GetStatusBarColor()
      if r and g and b then
        if r > 0.9 and g < 0.2 then return 3 end
        if r > 0.9 and g > 0.7 and b < 0.3 then return 2 end
        if g > 0.7 and r < 0.3 then return 1 end
      end
    end
    return 0
  end

  local function scan()
    local ws = WorldFrame:GetEffectiveScale()
    local W, H = WorldFrame:GetWidth() * ws, WorldFrame:GetHeight() * ws
    local hasTarget = UnitExists("target")
    local tname = hasTarget and UnitName("target") or nil
    local list = {}
    for _, f in ipairs({WorldFrame:GetChildren()}) do
      if not f:GetName() and f:IsShown() and f:GetNumChildren() > 0 then
        local cx, cy = f:GetCenter()
        if cx and cy then
          local sc = f:GetEffectiveScale()
          local name, level = info(f)
          local isTarget = hasTarget and name ~= nil and name == tname and f:GetAlpha() > 0.9
          list[#list + 1] = {
            fx = cx * sc / W, fy = cy * sc / H, tgt = isTarget and true or false,
            re = reaction(f), lvl = level, nh = name and nameHash(name) or 0,
          }
        end
      end
    end
    table.sort(list, function(a, b)
      if a.tgt ~= b.tgt then return a.tgt end
      return math.abs(a.fx - 0.5) < math.abs(b.fx - 0.5)
    end)
    NP[4] = #list
    NP[5] = hasTarget and 1 or 0
    for i = 1, SLOTS do
      local b = 6 + (i - 1) * 5
      local e = list[i]
      if e then
        NP[b] = e.fx
        NP[b + 1] = e.fy
        NP[b + 2] = (e.tgt and 1 or 0) + 2 * e.re
        NP[b + 3] = e.lvl
        NP[b + 4] = e.nh
      else
        NP[b] = 0
        NP[b + 1] = 0
        NP[b + 2] = 0
        NP[b + 3] = 0
        NP[b + 4] = 0
      end
    end
  end

  local pf = CreateFrame("Frame")
  pf:SetScript("OnUpdate", function(self, elapsed)
    NP[3] = GetTime()
    acc = acc + elapsed
    if acc < 0.1 then return end
    acc = 0
    local ok, err = pcall(scan)
    if not ok then
      NP[46] = NP[46] + 1
      if NP[46] <= 3 then print("LUA ERR plates", err) end
    end
  end)
end
