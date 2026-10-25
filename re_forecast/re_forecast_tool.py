# -*- coding: utf-8 -*-
"""
RE-Forecast Lite : 전국 태양광·풍력 시간별 발전량 AI 예측 + 사후 비교 툴
=========================================================================
흐름
  1) 데이터 적재 : KPX 5분 실시간 발전현황(목표값), KPX 지역별 시간별 발전량(지역 가중치·풍력 보조이력),
                   Open-Meteo 번들(archive=ERA5 재분석, prev=과거 실제 발표 예보 D-1/D-2, single=특정 발표 예보)
  2) 학습 기간   : 예측 시작일 직전 TRAIN_YEARS(기본 3년)만 사용. 예측 시작 이후 실측은 학습/튜닝에 절대 미사용
  3) 사전 검증   : 학습 마지막 ~60일을 홀드아웃 → '그 당시 실제 발표된 예보(prev d1/d2)'로 검증
                   → NWP 모델 가중, GBM/MLP 앙상블 가중, 수준(편향) 보정, P10~P90 구간 보정을 여기서만 결정
  4) 최종 학습   : 3년 전체 재학습 → 예측 기간을 예보 기상으로 예측
  5) 비교        : 예측 기간 실측을 그제서야 읽어 지표 계산 (MAE·RMSE·MBE·nMAE·KPX 오차율·스킬)

시간 규약 : 타임스탬프 = 1시간 구간의 '종료' 시각(KST). KPX 거래시간 h = (h-1)시~h시 = ts_end h시.
단위      : MWh/h (= 해당 시간 평균 출력 MW)

사용 예
  python re_forecast_tool.py --data-dir ./data --start 2026-10-17 --end 2026-10-23 --out ./outputs
"""
import argparse
import glob
import gzip
import io
import json
import os
import warnings
from types import SimpleNamespace

import numpy as np
import pandas as pd

warnings.filterwarnings('ignore')

try:
    import lightgbm as lgb
except ImportError:                                   # pragma: no cover
    lgb = None
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.neural_network import MLPRegressor
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler
from scipy.optimize import nnls

SOURCES = ('solar', 'wind')
SRC_KO = {'solar': '태양광', 'wind': '풍력'}
NWP_MODELS = ('ecmwf_ifs025', 'ecmwf_ifs', 'gfs_global')
SEED = 42

# 기상지점 코드 → KPX 지역명 (태양광 지역 가중치용)
REGION_OF = {'SEO': '서울', 'BUS': '부산', 'DAE': '대구', 'INC': '인천', 'GWJ': '광주', 'DJN': '대전',
             'ULS': '울산', 'SEJ': '세종', 'GGI': '경기', 'GWN': '강원', 'CBK': '충북', 'CNM': '충남',
             'JBK': '전북', 'JNM': '전남', 'GBK': '경북', 'GNM': '경남', 'JEJ': '제주'}
REGION_ALIAS = {'서울시': '서울', '부산시': '부산', '대구시': '대구', '인천시': '인천', '광주시': '광주',
                '대전시': '대전', '울산시': '울산', '세종시': '세종', '경기도': '경기', '강원도': '강원',
                '충청북도': '충북', '충청남도': '충남', '전라북도': '전북', '전라남도': '전남',
                '경상북도': '경북', '경상남도': '경남', '제주도': '제주', '제주': '제주', '육지': '육지'}
WIND_GROUPS = {'LAND': ['W_DGR', 'W_TBK', 'W_YDK', 'W_YGW'], 'JEJU': ['W_JW', 'W_JNE', 'W_JSE']}
# prev(과거 발표 예보)에 없는 변수는 학습에서도 제외 → 학습/예측 입력 일관성 유지
SOLAR_VARS = ['shortwave_radiation', 'direct_radiation', 'diffuse_radiation', 'temperature_2m',
              'relative_humidity_2m', 'cloud_cover', 'precipitation', 'wind_speed_10m']
WIND_VARS = ['wind_speed_100m', 'wind_direction_100m', 'wind_speed_10m', 'wind_direction_10m',
             'temperature_2m', 'surface_pressure', 'relative_humidity_2m']
CENTER = (36.3, 127.8)

# 변수 분류 (시나리오 비교 단위). 태양광 9종, 풍력 6종
GROUPS = {'solar': ['일사량', '운량', '기온', '습도', '강수', '바람', '태양고도', '계절', '시각'],
          'wind': ['바람', '기온', '습도', '기압', '계절', '시각']}
# 기본 모델: 풍력은 습도 미사용 (최초 리포트와 동일한 구성)
DEFAULT_DROP = {'wind': ('습도',)}


def feature_group(src, col):
    """피처 컬럼 → 변수 분류"""
    if col in ('hour', 'hr_s', 'hr_c'):
        return '시각'
    if col in ('doy_s', 'doy_c'):
        return '계절'
    if src == 'solar':
        if col in ('cosz', 'clear_sky'):
            return '태양고도'
        if col.startswith('nat_'):
            v = col[4:]
            return {'shortwave_radiation': '일사량', 'direct_radiation': '일사량', 'diffuse_radiation': '일사량',
                    'kt': '일사량', 'cloud_cover': '운량', 'temperature_2m': '기온',
                    'relative_humidity_2m': '습도', 'precipitation': '강수', 'wind_speed_10m': '바람'}[v]
        return {'sw': '일사량', 'dif': '일사량', 'cc': '운량'}[col.rsplit('_', 1)[1]]
    if col.startswith('dp_') or col.endswith('_ps'):
        return '기압'
    if col.endswith('_t'):
        return '기온'
    if col.endswith('_rh'):
        return '습도'
    return '바람'


def log(msg):
    print(msg, flush=True)


# =====================================================================================
# 1. 데이터 적재
# =====================================================================================
def _num(s):
    return pd.to_numeric(s.astype(str).str.replace(',', '', regex=False).str.strip()
                         .replace({'': np.nan, 'nan': np.nan}), errors='coerce')


def load_5min(data_dir, inventory):
    """KPX 발전원별 발전현황(5분, 전국) → 시간별 평균 MW(=MWh). 무효 구간·중복·음수 정리."""
    frames = []
    for fp in sorted(glob.glob(os.path.join(data_dir, '*5min*.csv'))):
        x = pd.read_csv(fp, dtype={'regDate': str})
        n0 = len(x)
        x = x[x['regDate'].str.match(r'^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$', na=False)].copy()
        x['regDate'] = pd.to_datetime(x['regDate'], format='%Y-%m-%d %H:%M')
        inventory.append({'파일': os.path.basename(fp), '용도': '목표값(실측) - 5분 전국',
                          '기간': f'{x.regDate.min():%Y-%m-%d} ~ {x.regDate.max():%Y-%m-%d}',
                          '행수': len(x), '비고': f'날짜 무효 행 {n0 - len(x)}개 제거'})
        frames.append(x)
    x = pd.concat(frames).drop_duplicates('regDate').set_index('regDate').sort_index()
    x = x[['sunlight', 'windPower']].astype(float)
    x['sunlight'] = x['sunlight'].clip(lower=0)              # 새벽·저녁 미소 음수
    # 데이터 공급 전 '전부 0' 구간은 결측 처리 (sunlight 2022-05-19 이전, windPower 2024-11-22 이전)
    for c in x.columns:
        dmax = x[c].resample('D').max()
        first = dmax[dmax > 0].index.min()
        x.loc[x.index < first, c] = np.nan
    g = x.resample('h', closed='right', label='right')
    h = g.mean()
    cnt = g.count()
    h = h.where(cnt >= 10)                                    # 12개 중 10개 이상 있을 때만
    return h.rename(columns={'sunlight': 'solar', 'windPower': 'wind'})


def _read_kpx(fp):
    for enc in ('cp949', 'utf-8-sig'):
        try:
            df = pd.read_csv(fp, encoding=enc, dtype=str)
            df.columns = [c.strip() for c in df.columns]
            return df
        except UnicodeDecodeError:
            continue
    raise ValueError(fp)


def load_kpx_hourly(data_dir, t0, inventory):
    """KPX 지역별 시간별 태양광·풍력(전력거래량) → 전국 합계 + 지역별 태양광. t0 이후만."""
    sol, win, reg = [], [], []
    for fp in sorted(glob.glob(os.path.join(data_dir, '*kpx*.csv'))):
        if '5min' in fp:
            continue
        df = _read_kpx(fp)
        ts = pd.to_datetime(df.iloc[:, 0].str.strip()) + pd.to_timedelta(_num(df.iloc[:, 1]), unit='h')
        region = df.iloc[:, 2].str.strip().map(lambda s: REGION_ALIAS.get(s, s))
        name = os.path.basename(fp)
        info = {'파일': name, '기간': f'{ts.min():%Y-%m-%d} ~ {ts.max():%Y-%m-%d}', '행수': len(df)}
        if ts.max() <= t0:
            inventory.append({**info, '용도': '미사용 (학습 3년 범위 밖)', '비고': ''})
            continue
        if '연료원' in df.columns:                                     # 롱 포맷
            v, f = _num(df.iloc[:, -1]), df['연료원'].str.strip()
            s_ = pd.DataFrame({'ts': ts, 'region': region, 'v': v})[f == '태양광']
            w_ = pd.DataFrame({'ts': ts, 'region': region, 'v': v})[f == '풍력']
        elif df.shape[1] >= 5:                                          # 와이드: 태양광·풍력 2열
            s_ = pd.DataFrame({'ts': ts, 'region': region, 'v': _num(df.iloc[:, 3])})
            w_ = pd.DataFrame({'ts': ts, 'region': region, 'v': _num(df.iloc[:, 4])})
        else:
            inventory.append({**info, '용도': '미사용', '비고': '연료원 구분 없음(kWh 태양광 단일열)'})
            continue
        s_ = s_[~s_.region.isin(['육지'])]
        sol.append(s_.groupby('ts').v.sum(min_count=1))
        win.append(w_.groupby('ts').v.sum(min_count=1))
        reg.append(s_)
        inventory.append({**info, '용도': '지역 가중치 + 풍력 보조이력', '비고': '천단위 쉼표 처리'})
    nat = pd.DataFrame({'solar': pd.concat(sol), 'wind': pd.concat(win)})
    nat = nat[~nat.index.duplicated(keep='last')].sort_index()
    regdf = pd.concat(reg)
    return nat[nat.index > t0], regdf[regdf.ts > t0]


def load_bundle(data_dir, inventory):
    fp = sorted(glob.glob(os.path.join(data_dir, '*openmeteo*.json.gz')))[0]
    with gzip.open(fp, 'rt', encoding='utf-8') as fh:
        B = json.load(fh)
    inventory.append({'파일': os.path.basename(fp), '용도': '기상 입력 (재분석·과거예보·발표예보)',
                      '기간': B['meta'].get('archive', ''), '행수': '-',
                      '비고': f"prev: {B['meta'].get('prev', '')} / single: {B['meta'].get('single', '')}"})
    return B


def _frame(block, vars_):
    idx = pd.date_range(block['t0'], periods=block['n'], freq='h')
    return pd.DataFrame({v: pd.to_numeric(pd.Series(block.get(v, [None] * block['n'])), errors='coerce').values
                         for v in vars_}, index=idx)


def weather_set(B, kind, model=None, lead=None):
    """kind: archive | prev | single → {point: DataFrame}"""
    out = {}
    src = B['archive'] if kind == 'archive' else B[kind][model]
    for p, blk in src.items():
        if kind == 'prev':
            blk = blk[lead]
        vars_ = SOLAR_VARS if p.startswith('S_') else WIND_VARS
        out[p] = _frame(blk, vars_)
    return out


def merge_weather(primary, fallback):
    """primary 결측(예: 발표 전 시간, 첫 시간 None)을 fallback 으로 채움"""
    out = {}
    for p, df in primary.items():
        fb = fallback[p]
        idx = df.index.union(fb.index)
        out[p] = df.reindex(idx).combine_first(fb.reindex(idx))
    return out


# =====================================================================================
# 2. 피처
# =====================================================================================
def cos_zenith(idx_end, lat, lon, n_sub=4):
    """구간(ts_end-1h, ts_end] 평균 태양천정각 cos (NOAA 근사식)"""
    acc = np.zeros(len(idx_end))
    for k in range(n_sub):
        t = idx_end - pd.Timedelta(minutes=60 * (k + 0.5) / n_sub) - pd.Timedelta(hours=9)   # → UTC
        doy = t.dayofyear.values
        hr = t.hour.values + t.minute.values / 60.0
        g = 2 * np.pi / 365 * (doy - 1 + (hr - 12) / 24)
        eqt = 229.18 * (0.000075 + 0.001868 * np.cos(g) - 0.032077 * np.sin(g)
                        - 0.014615 * np.cos(2 * g) - 0.040849 * np.sin(2 * g))
        decl = (0.006918 - 0.399912 * np.cos(g) + 0.070257 * np.sin(g) - 0.006758 * np.cos(2 * g)
                + 0.000907 * np.sin(2 * g) - 0.002697 * np.cos(3 * g) + 0.00148 * np.sin(3 * g))
        ha = np.radians((hr * 60 + eqt + 4 * lon) / 4 - 180)
        la = np.radians(lat)
        acc += np.clip(np.sin(la) * np.sin(decl) + np.cos(la) * np.cos(decl) * np.cos(ha), 0, None)
    return acc / n_sub


def clear_sky(cz):
    with np.errstate(divide='ignore', invalid='ignore'):
        return np.where(cz > 0.01, 1098 * cz * np.exp(-0.059 / np.maximum(cz, 0.01)), 0.0)


def power_curve(v):
    """범용 풍력 출력곡선(정규화): 3m/s 시동, 12.5m/s 정격, 25m/s 정지"""
    v = np.asarray(v, float)
    return np.where(v < 3, 0, np.where(v < 12.5, ((v - 3) / 9.5) ** 3, np.where(v < 25, 1.0, 0.0)))


def calendar(idx):
    doy = idx.dayofyear.values
    hr = idx.hour.values
    return pd.DataFrame({'hour': hr, 'doy_s': np.sin(2 * np.pi * doy / 365.25),
                         'doy_c': np.cos(2 * np.pi * doy / 365.25),
                         'hr_s': np.sin(2 * np.pi * hr / 24), 'hr_c': np.cos(2 * np.pi * hr / 24)}, index=idx)


def solar_features(wx, idx, w_pts):
    cz = cos_zenith(idx, *CENTER)
    cs = clear_sky(cz)
    F = calendar(idx)
    F['cosz'] = cz
    F['clear_sky'] = cs
    pts = [p for p in w_pts if p in wx]
    W = np.array([w_pts[p] for p in pts])
    W = W / W.sum()
    for v in SOLAR_VARS:
        M = np.column_stack([wx[p][v].reindex(idx).values for p in pts])
        F[f'nat_{v}'] = np.nansum(M * W, axis=1) / np.sum(W * ~np.isnan(M), axis=1)
    F['nat_kt'] = np.where(cs > 20, F['nat_shortwave_radiation'] / np.maximum(cs, 1), np.nan)
    F['nat_kt'] = F['nat_kt'].clip(0, 1.5)
    for p in pts:                                              # 지점별 일사·운량
        F[f'{p}_sw'] = wx[p]['shortwave_radiation'].reindex(idx).values
        F[f'{p}_dif'] = wx[p]['diffuse_radiation'].reindex(idx).values
        F[f'{p}_cc'] = wx[p]['cloud_cover'].reindex(idx).values
    return F


def wind_features(wx, idx, gw):
    F = calendar(idx)
    grp = {}
    for g, pts in WIND_GROUPS.items():
        pcs, ws = [], []
        for p in pts:
            d = wx[p].reindex(idx)
            v = d['wind_speed_100m'].values
            dr = np.radians(d['wind_direction_100m'].values)
            F[f'{p}_ws100'] = v
            F[f'{p}_u'] = -v * np.sin(dr)
            F[f'{p}_v'] = -v * np.cos(dr)
            F[f'{p}_ws10'] = d['wind_speed_10m'].values
            F[f'{p}_shear'] = v / np.maximum(d['wind_speed_10m'].values, 0.5)
            F[f'{p}_t'] = d['temperature_2m'].values
            F[f'{p}_ps'] = d['surface_pressure'].values
            F[f'{p}_rh'] = d['relative_humidity_2m'].values
            F[f'{p}_pc'] = power_curve(v)
            pcs.append(F[f'{p}_pc'].values)
            ws.append(v)
        F[f'{g}_pc'] = np.nanmean(pcs, axis=0)
        F[f'{g}_ws'] = np.nanmean(ws, axis=0)
        F[f'{g}_ws3'] = np.nanmean(np.clip(np.array(ws), 0, 25) ** 3, axis=0)
        grp[g] = F[f'{g}_pc'].values
    F['nat_pc'] = gw['LAND'] * grp['LAND'] + gw['JEJU'] * grp['JEJU']
    # 압력경도(지점 간 기압차) - 종관 바람 강도 대리변수
    F['dp_land'] = F['W_DGR_ps'] - F['W_YGW_ps']
    F['dp_jeju'] = F['W_JNE_ps'] - F['W_JW_ps']
    return F


def potential(src, wx, idx, w_pts, gw):
    """정규화용 '기상 잠재 발전' 지수 (태양광: 가중 일사, 풍력: 가중 출력곡선)"""
    if src == 'solar':
        return pd.Series(solar_features(wx, idx, w_pts)['nat_shortwave_radiation'].values, index=idx)
    return pd.Series(wind_features(wx, idx, gw)['nat_pc'].values, index=idx)


def capacity_index(y, pot, window_days):
    """설비 증가 반영: 일별 [실측합/잠재합]의 과거 window 일 비율 (당일 미포함 → 누설 없음)"""
    yd = y.resample('D', closed='right', label='left').sum(min_count=12)
    pd_ = pot.resample('D', closed='right', label='left').sum(min_count=12)
    ok = yd.notna() & pd_.notna()
    ys = yd.where(ok, 0).rolling(window_days, min_periods=min(30, window_days)).sum().shift(1)
    ps = pd_.where(ok, 0).rolling(window_days, min_periods=min(30, window_days)).sum().shift(1)
    return (ys / ps).replace([np.inf, -np.inf], np.nan)


def cap_at(ci, idx):
    """시간 인덱스(ts_end)에 해당 거래일의 용량지수 매핑"""
    day = (idx - pd.Timedelta(minutes=1)).normalize()
    return pd.Series(ci.reindex(day).values, index=idx)


# =====================================================================================
# 3. 모델
# =====================================================================================
def gbm(objective='regression', alpha=None, n_estimators=2000):
    if lgb is not None:
        p = dict(n_estimators=n_estimators, learning_rate=0.03, num_leaves=48, min_child_samples=40,
                 subsample=0.8, subsample_freq=1, colsample_bytree=0.7, reg_lambda=1.0,
                 random_state=SEED, verbose=-1, n_jobs=4)
        if objective == 'quantile':
            p.update(objective='quantile', alpha=alpha)
        return lgb.LGBMRegressor(**p)
    loss = 'quantile' if objective == 'quantile' else 'squared_error'
    return HistGradientBoostingRegressor(loss=loss, quantile=alpha, max_iter=n_estimators,
                                         learning_rate=0.05, random_state=SEED)


def fit_gbm(X, y, X_es=None, y_es=None, **kw):
    m = gbm(**kw)
    if lgb is not None and X_es is not None:
        m.fit(X, y, eval_set=[(X_es, y_es)], callbacks=[lgb.early_stopping(150, verbose=False)])
    else:
        m.fit(X, y)
    return m


def best_iter(m, default):
    return int(getattr(m, 'best_iteration_', None) or default)


def fit_mlp(X, y):
    m = make_pipeline(StandardScaler(),
                      MLPRegressor(hidden_layer_sizes=(128, 64), alpha=1e-3, learning_rate_init=1e-3,
                                   max_iter=300, early_stopping=True, n_iter_no_change=15,
                                   random_state=SEED))
    m.fit(X.fillna(X.median()), y)
    return m


class SourceModel:
    """한 발전원(태양광/풍력)의 학습·예측 단위: z = y / 용량지수 를 학습"""

    def __init__(self, src, n_iter=None, drop=(), quantiles=True):
        self.src = src
        self.n_iter = n_iter
        self.drop = tuple(drop)
        self.quantiles = quantiles

    def _mask(self, F):
        return (F['clear_sky'] > 0).values if self.src == 'solar' else np.ones(len(F), bool)

    def fit(self, F, y, cap, es_days=60):
        z = (y / cap).replace([np.inf, -np.inf], np.nan)
        ok = z.notna().values & self._mask(F) & F.notna().mean(axis=1).gt(0.8).values
        self.cols = [c for c in F.columns if feature_group(self.src, c) not in self.drop]
        X, z = F.loc[ok, self.cols], z[ok]
        self.med = X.median()
        if self.n_iter is None:                           # 마지막 es_days 로 조기종료 → 반복수 결정
            cut = X.index.max() - pd.Timedelta(days=es_days)
            tr, es = X.index <= cut, X.index > cut
            m = fit_gbm(X[tr], z[tr], X[es], z[es])
            self.n_iter = max(200, int(best_iter(m, 1500) * 1.1))
        self.gbm = fit_gbm(X, z, n_estimators=self.n_iter)
        if self.quantiles:
            self.q10 = fit_gbm(X, z, objective='quantile', alpha=0.1, n_estimators=self.n_iter)
            self.q90 = fit_gbm(X, z, objective='quantile', alpha=0.9, n_estimators=self.n_iter)
        else:                                             # 시나리오 비교용: 구간 모델 생략
            self.q10 = self.q90 = self.gbm
        self.mlp = fit_mlp(X, z)
        self.zmax = float(np.nanquantile(z, 0.999)) * 1.1
        return self

    def predict(self, F, cap):
        X = F[self.cols]
        m = self._mask(F)
        out = {}
        for k, mdl in (('gbm', self.gbm), ('mlp', self.mlp), ('q10', self.q10), ('q90', self.q90)):
            Xi = X.fillna(self.med) if k == 'mlp' else X
            z = np.clip(mdl.predict(Xi), 0, self.zmax)
            out[k] = pd.Series(np.where(m, z, 0.0) * cap.values, index=F.index)
        return pd.DataFrame(out)

    def importance(self, top=15):
        if lgb is None:
            return pd.Series(dtype=float)
        imp = pd.Series(self.gbm.booster_.feature_importance('gain'), index=self.cols)
        return (imp / imp.sum() * 100).sort_values(ascending=False).head(top)


# =====================================================================================
# 4. 지표
# =====================================================================================
def metrics(y, p, cap_mw):
    d = pd.DataFrame({'y': y, 'p': p}).dropna()
    e = d.p - d.y
    hi = d.y >= 0.1 * cap_mw
    ss = ((d.y - d.y.mean()) ** 2).sum()
    return {
        'MAE(MWh)': e.abs().mean(), 'RMSE(MWh)': np.sqrt((e ** 2).mean()), 'MBE(MWh)': e.mean(),
        'nMAE(%,용량)': e.abs().mean() / cap_mw * 100, 'nRMSE(%,용량)': np.sqrt((e ** 2).mean()) / cap_mw * 100,
        'KPX오차율(%)': (e[hi].abs() / cap_mw).mean() * 100 if hi.any() else np.nan,
        'MAPE(%,이용률10%↑)': (e[hi].abs() / d.y[hi]).mean() * 100 if hi.any() else np.nan,
        'R2': 1 - (e ** 2).sum() / ss if ss > 0 else np.nan,
        '총량오차(%)': (d.p.sum() - d.y.sum()) / d.y.sum() * 100, 'n': len(d)}


# =====================================================================================
# 5. 실행
# =====================================================================================
def prepare(data_dir, start, end, train_years):
    """데이터 적재·정답값·기상 피처 준비 (시나리오 간 공유)"""
    start, end = pd.Timestamp(start), pd.Timestamp(end)
    issue = start                                              # 예측 시작 거래일 0시 = 정보 차단 시점
    t0 = issue - pd.DateOffset(years=train_years)              # 학습 시작
    fidx = pd.date_range(start + pd.Timedelta(hours=1), end + pd.Timedelta(days=1), freq='h')
    inv = []

    log(f'[1] 데이터 적재  학습 {t0:%Y-%m-%d} ~ {issue - pd.Timedelta(days=1):%Y-%m-%d} / 예측 {start:%Y-%m-%d} ~ {end:%Y-%m-%d}')
    Y5_all = load_5min(data_dir, inv)
    Y5 = Y5_all[(Y5_all.index > t0) & (Y5_all.index <= issue)]          # ★ 예측 시작 이후 실측 차단
    KH, REG = load_kpx_hourly(data_dir, t0, inv)
    KH = KH[KH.index <= issue]
    B = load_bundle(data_dir, inv)

    # 태양광 지점 가중치 = 최근 1년 KPX 지역별 태양광 발전량 비중
    last = REG[REG.ts > REG.ts.max() - pd.Timedelta(days=365)]
    share = last.groupby('region').v.sum()
    share = share / share.sum()
    sol_pts = [p for p in B['archive'] if p.startswith('S_')]
    npts = pd.Series([REGION_OF[p[2:5]] for p in sol_pts]).value_counts()
    w_pts = {p: share.get(REGION_OF[p[2:5]], 0) / npts[REGION_OF[p[2:5]]] for p in sol_pts}
    # 풍력 육지/제주 가중치 = KPX 지역별 풍력 비중 (최근 1년)
    try:
        gw = _wind_share(data_dir)
    except ValueError:
        gw = {'LAND': 0.8, 'JEJU': 0.2}

    # 목표값: 태양광 = 5분 실측, 풍력 = 5분 실측(2024-11-23~) + 그 이전은 KPX 시간별 × 기준보정계수
    Y = pd.DataFrame(index=pd.date_range(t0 + pd.Timedelta(hours=1), issue, freq='h'))
    Y['solar'] = Y5['solar'].reindex(Y.index)
    w5 = Y5['wind'].reindex(Y.index)
    ov = w5.dropna().index.intersection(KH['wind'].dropna().index)
    ov = ov[ov <= ov.min() + pd.Timedelta(days=90)] if len(ov) else ov
    k_wind = float(w5[ov].sum() / KH['wind'][ov].sum()) if len(ov) else 1.0
    Y['wind'] = w5.combine_first(KH['wind'].reindex(Y.index) * k_wind)
    wind_note = f'5분 실측 {w5.first_valid_index():%Y-%m-%d}~, 이전 구간은 KPX 시간별 풍력×{k_wind:.3f}(겹침 90일 기준 보정)'

    log('[2] 기상 피처 (학습=ERA5 재분석, 검증·예측=실제 발표 예보)')
    WX_arch = weather_set(B, 'archive')
    WX_arch = {p: d[d.index <= issue] for p, d in WX_arch.items()}       # ★ 예측기간 관측기상 차단
    hidx = Y.index
    FEAT = {'solar': lambda wx, idx: solar_features(wx, idx, w_pts),
            'wind': lambda wx, idx: wind_features(wx, idx, gw)}
    F_arch = {s: FEAT[s](WX_arch, hidx) for s in SOURCES}
    POT = {s: potential(s, WX_arch, hidx, w_pts, gw) for s in SOURCES}
    CAPW = {'solar': 60, 'wind': 180}
    CI = {s: capacity_index(Y[s], POT[s], CAPW[s]) for s in SOURCES}

    # 사전 검증 구간: prev 예보 시작 ~ 예측 시작 전일
    prev_t0 = pd.Timestamp(B['prev'][NWP_MODELS[0]]['S_SEO1']['d1']['t0'])
    v0 = max(prev_t0.normalize() + pd.Timedelta(days=1), issue - pd.Timedelta(days=63))
    vidx = hidx[hidx > v0]
    tr_idx = hidx[hidx <= v0]
    log(f'[3] 사전 검증  학습 ~{v0 - pd.Timedelta(days=1):%Y-%m-%d} / 검증 {v0:%Y-%m-%d} ~ {issue - pd.Timedelta(days=1):%Y-%m-%d} (실제 발표 예보 D-1·D-2 입력)')

    PREV = {(m, l): weather_set(B, 'prev', m, l) for m in NWP_MODELS for l in ('d1', 'd2')}
    SING = {m: merge_weather(weather_set(B, 'single', m), PREV[(m, 'd1')]) for m in NWP_MODELS}

    return SimpleNamespace(**{k: v for k, v in locals().items()})


def model_source(C, s, drop=(), quantiles=True):
    """한 발전원: 사전검증(가중·보정 결정) → 3년 재학습 → 예측. drop = 제외할 변수 분류"""
    F_arch, Y, CI, FEAT, PREV, SING = C.F_arch, C.Y, C.CI, C.FEAT, C.PREV, C.SING
    tr_idx, vidx, v0, hidx, fidx, issue = C.tr_idx, C.vidx, C.v0, C.hidx, C.fidx, C.issue
    R = {}
    cap_tr = cap_at(CI[s], tr_idx)
    M = SourceModel(s, drop=drop, quantiles=quantiles).fit(F_arch[s].loc[tr_idx], Y[s].loc[tr_idx], cap_tr)
    cap_v = pd.Series(cap_at(CI[s], pd.DatetimeIndex([v0 + pd.Timedelta(hours=1)])).iloc[0], index=vidx)
    yv = Y[s].loc[vidx]
    val = {'archive': M.predict(F_arch[s].loc[vidx], cap_v)}
    for (m, l), wx in PREV.items():
        val[f'{m}|{l}'] = M.predict(FEAT[s](wx, vidx), cap_v)
    # NWP 모델 가중: D-1 예보 GBM 의 MAE 역수
    mae = {m: (val[f'{m}|d1']['gbm'] - yv).abs().mean() for m in NWP_MODELS}
    wm = {m: 1 / mae[m] for m in NWP_MODELS}
    tot = sum(wm.values())
    wm = {m: v / tot for m, v in wm.items()}

    def nwp_mix(lead, col):
        return sum(wm[m] * val[f'{m}|{lead}'][col] for m in NWP_MODELS)
    # GBM·MLP 앙상블 가중 (NNLS, D-1·D-2 예보 입력)
    A = np.vstack([np.r_[nwp_mix('d1', c).values, nwp_mix('d2', c).values] for c in ('gbm', 'mlp')]).T
    b = np.r_[yv.values, yv.values]
    ok = ~np.isnan(b) & ~np.isnan(A).any(axis=1)
    wa, _ = nnls(A[ok], b[ok])
    wa = wa / wa.sum() if wa.sum() > 0 else np.array([1.0, 0.0])
    ens = {lead: wa[0] * nwp_mix(lead, 'gbm') + wa[1] * nwp_mix(lead, 'mlp') for lead in ('d1', 'd2')}
    # 수준 보정(MOS): 재분석 학습 ↔ 예보 입력 간 계통편차
    bias = float(np.clip(yv.sum() / pd.concat([ens['d1'], ens['d2']]).groupby(level=0).mean()
                         .reindex(yv.dropna().index).sum(), 0.85, 1.2))
    # P10/P90 구간 보정 (CQR): 예보 입력 기준 커버리지 80% 목표
    q10 = nwp_mix('d1', 'q10') * bias
    q90 = nwp_mix('d1', 'q90') * bias
    E = np.maximum(q10 - yv, yv - q90).dropna()
    if s == 'solar':
        E = E[F_arch[s].loc[E.index, 'clear_sky'] > 0]
    cqr = float(np.quantile(E, 0.8))
    capmw = float(np.nanquantile(Y[s].loc[hidx], 0.999))
    R['val'] = {
        'NWP가중': wm, '앙상블가중': {'GBM': wa[0], 'MLP': wa[1]}, '수준보정': bias, 'CQR': cqr,
        'table': pd.DataFrame({
            '재분석기상(모델성능)': metrics(yv, val['archive']['gbm'], capmw),
            **{f'{m} D-1 GBM': metrics(yv, val[f'{m}|d1']['gbm'], capmw) for m in NWP_MODELS},
            '앙상블 D-1(보정)': metrics(yv, ens['d1'] * bias, capmw),
            '앙상블 D-2(보정)': metrics(yv, ens['d2'] * bias, capmw)}).T,
        'series': pd.DataFrame({'actual': yv, 'pred_d1': ens['d1'] * bias, 'pred_archive': val['archive']['gbm']})}

    # ---------------- 최종 학습 (3년 전체) → 예측 ----------------
    cap_all = cap_at(CI[s], hidx)
    Mf = SourceModel(s, n_iter=M.n_iter, drop=drop, quantiles=quantiles).fit(F_arch[s], Y[s], cap_all)
    cap_issue = CI[s].dropna().iloc[-1]                              # 예측 직전 용량지수 (고정)
    cap_f = pd.Series(cap_issue, index=fidx)
    P = {m: Mf.predict(FEAT[s](SING[m], fidx), cap_f) for m in NWP_MODELS}
    mix = lambda c: sum(wm[m] * P[m][c] for m in NWP_MODELS)
    p50 = (wa[0] * mix('gbm') + wa[1] * mix('mlp')) * bias
    lo = (mix('q10') * bias - cqr).clip(lower=0)
    hi = mix('q90') * bias + cqr
    if s == 'solar':
        night = FEAT[s](SING[NWP_MODELS[0]], fidx)['clear_sky'].values <= 0
        lo[night] = 0
        hi[night] = 0
    lo, hi = np.minimum(lo, p50), np.maximum(hi, p50)
    # 일 단위 롤링 D-1 예보 (매일 전날 발표 예보로 그날 예측)
    P1 = {m: Mf.predict(FEAT[s](PREV[(m, 'd1')], fidx), cap_f) for m in NWP_MODELS}
    mix1 = lambda c: sum(wm[m] * P1[m][c] for m in NWP_MODELS)
    p_d1 = (wa[0] * mix1('gbm') + wa[1] * mix1('mlp')) * bias
    # 기준(베이스라인) 예측: 실측은 예측 시작 전까지만 사용
    hist = Y[s].loc[:issue]
    last_day = hist.iloc[-24:].values
    persist = pd.Series(np.tile(last_day, len(fidx) // 24), index=fidx)
    prof = hist.iloc[-24 * 7:].groupby(hist.iloc[-24 * 7:].index.hour).mean()
    avg7 = pd.Series(prof.reindex(fidx.hour).values, index=fidx)
    R['fc'] = pd.DataFrame({'AI예측(P50)': p50, 'P10': lo, 'P90': hi, 'AI_D-1롤링': p_d1,
                                 **{f'NWP_{m}': (wa[0] * P[m]['gbm'] + wa[1] * P[m]['mlp']) * bias
                                    for m in NWP_MODELS},
                                 '기준_전일지속': persist, '기준_7일평균': avg7})
    R['imp'] = Mf.importance()
    R['final'] = (Mf, cap_f, wa, bias)
    R['meta'] = {'cap_index': float(cap_issue), 'capMW': capmw, 'n_iter': M.n_iter,
                      'train_rows': int(Y[s].notna().sum())}
    return R


def run(data_dir, start, end, train_years, out_dir, drop=None):
    os.makedirs(out_dir, exist_ok=True)
    C = prepare(data_dir, start, end, train_years)
    drop = DEFAULT_DROP if drop is None else drop
    res = {'meta': {}, 'val': {}, 'fc': {}, 'imp': {}, 'hind': {}}
    FINAL = {}
    for s in SOURCES:
        log(f'  - {SRC_KO[s]}')
        R = model_source(C, s, drop=drop.get(s, ()))
        for k in ('val', 'fc', 'imp', 'meta'):
            res[k][s] = R[k]
        FINAL[s] = R['final']
    res['meta']['wind_note'] = C.wind_note
    res['meta']['k_wind'] = C.k_wind
    B, FEAT, fidx, Y5_all, inv = C.B, C.FEAT, C.fidx, C.Y5_all, C.inv
    t0, issue, v0, start, end = C.t0, C.issue, C.v0, C.start, C.end
    w_pts, gw = C.w_pts, C.gw

    # 예측 저장 후에만: 예측기간 '관측 기상(ERA5)'으로 사후예측 → 오차 분해용 (예측 성능 아님, 발전 실측 미사용)
    WX_full = weather_set(B, 'archive')
    for s in SOURCES:
        Mf, cap_f, wa, bias = FINAL[s]
        ph = Mf.predict(FEAT[s](WX_full, fidx), cap_f)
        res['fc'][s]['사후_관측기상'] = (wa[0] * ph['gbm'] + wa[1] * ph['mlp']) * bias

    # ---------------- 비교 단계 : 여기서 처음으로 예측기간 실측을 읽음 ----------------
    log('[4] 비교 (예측 완료 후 실측 로드)')
    actual = Y5_all.reindex(fidx)
    out = {}
    for s in SOURCES:
        fc = res['fc'][s].copy()
        fc.insert(0, '실측', actual[s])
        out[s] = fc
    res['actual_available'] = bool(actual.notna().any().any())
    res['inventory'] = pd.DataFrame(inv)
    res['range'] = {'train': f'{t0:%Y-%m-%d} ~ {issue - pd.Timedelta(days=1):%Y-%m-%d}',
                    'forecast': f'{start:%Y-%m-%d} ~ {end:%Y-%m-%d}', 'val': f'{v0:%Y-%m-%d} ~ {issue - pd.Timedelta(days=1):%Y-%m-%d}'}
    res['w_pts'] = w_pts
    res['gw'] = gw
    return res, out


def _wind_share(data_dir):
    """KPX 지역별 풍력(육지/제주) 최근 1년 비중"""
    for fp in sorted(glob.glob(os.path.join(data_dir, '*kpx*.csv')), reverse=True):
        if '5min' in fp:
            continue
        df = _read_kpx(fp)
        if '연료원' not in df.columns:
            continue
        r = df.iloc[:, 2].str.strip().map(lambda s: REGION_ALIAS.get(s, s))
        w = df[df['연료원'].str.strip() == '풍력']
        tot = _num(w.iloc[:, -1]).groupby(r[w.index]).sum()
        land, jeju = tot.get('육지', 0), tot.get('제주', 0)
        if land + jeju > 0:
            return {'LAND': land / (land + jeju), 'JEJU': jeju / (land + jeju)}
    raise ValueError('풍력 지역 데이터 없음')


# =====================================================================================
# 6. 저장
# =====================================================================================
def save_outputs(res, out, out_dir, tag):
    os.makedirs(out_dir, exist_ok=True)
    xlsx = os.path.join(out_dir, f'RE_forecast_{tag}.xlsx')
    summary, daily, mt = [], {}, {}
    for s in SOURCES:
        d = out[s]
        capmw = res['meta'][s]['capMW']
        cols = [c for c in d.columns if c not in ('실측', 'P10', 'P90')]
        if d['실측'].notna().any():
            mt[s] = pd.DataFrame({c: metrics(d['실측'], d[c], capmw) for c in cols}).T
            hi = d['실측'].notna()
            cover = ((d['실측'] >= d['P10']) & (d['실측'] <= d['P90']))[hi]
            if s == 'solar':
                cover = cover[d.loc[hi, 'P90'] > 0]
            mt[s]['P10~P90적중(%)'] = np.nan
            mt[s].loc['AI예측(P50)', 'P10~P90적중(%)'] = cover.mean() * 100
        dd = d.groupby((d.index - pd.Timedelta(minutes=1)).date).sum(min_count=1) / 1000
        dd.index.name = '거래일'
        daily[s] = dd
    with pd.ExcelWriter(xlsx, engine='openpyxl') as xw:
        info = pd.DataFrame([
            ('학습 기간', res['range']['train']), ('사전 검증 기간', res['range']['val']),
            ('예측 기간', res['range']['forecast']), ('목표값', 'KPX 발전원별 발전현황(5분) 전국 시간평균 MW = MWh'),
            ('풍력 이력', res['meta']['wind_note'])] +
            [(f'{SRC_KO[s]} 설비지수(예측 시점)', round(res['meta'][s]['cap_index'], 3)) for s in SOURCES] +
            [(f'{SRC_KO[s]} 최대출력 기준(MW)', round(res['meta'][s]['capMW'])) for s in SOURCES] +
            [(f'{SRC_KO[s]} NWP 가중', json.dumps({k: round(v, 3) for k, v in res['val'][s]['NWP가중'].items()})) for s in SOURCES] +
            [(f'{SRC_KO[s]} GBM/MLP 가중', json.dumps({k: round(v, 3) for k, v in res['val'][s]['앙상블가중'].items()})) for s in SOURCES] +
            [(f'{SRC_KO[s]} 수준보정', round(res['val'][s]['수준보정'], 3)) for s in SOURCES],
            columns=['항목', '값'])
        info.to_excel(xw, sheet_name='요약', index=False)
        for s in SOURCES:
            if s in mt:
                mt[s].round(3).to_excel(xw, sheet_name=f'{SRC_KO[s]}_지표')
            daily[s].round(2).to_excel(xw, sheet_name=f'{SRC_KO[s]}_일별(GWh)')
            o = out[s].copy()
            o.index.name = 'ts_end(KST)'
            o.insert(0, '거래일', (o.index - pd.Timedelta(minutes=1)).date)
            o.insert(1, '거래시간', ((o.index.hour - 1) % 24) + 1)
            o.round(1).to_excel(xw, sheet_name=f'{SRC_KO[s]}_시간별')
            res['val'][s]['table'].round(3).to_excel(xw, sheet_name=f'{SRC_KO[s]}_사전검증')
            res['imp'][s].round(2).rename('중요도(%)').to_excel(xw, sheet_name=f'{SRC_KO[s]}_피처중요도')
        res['inventory'].to_excel(xw, sheet_name='사용데이터', index=False)
    # 리포트용 JSON
    js = {'range': res['range'], 'meta': res['meta'], 'gw': res['gw'],
          'w_pts': {k: round(v, 4) for k, v in res['w_pts'].items()}, 'inventory': res['inventory'].to_dict('records')}
    for s in SOURCES:
        js[s] = {'hourly': {'ts': [f'{t:%m-%d %H}' for t in out[s].index],
                            **{c: [None if pd.isna(v) else round(float(v), 1) for v in out[s][c]] for c in out[s].columns}},
                 'daily': {'day': [str(x) for x in daily[s].index],
                           **{c: [None if pd.isna(v) else round(float(v), 2) for v in daily[s][c]] for c in daily[s].columns}},
                 'metrics': mt[s].round(3).reset_index().rename(columns={'index': 'model'}).to_dict('records') if s in mt else [],
                 'val': {'table': res['val'][s]['table'].round(3).reset_index().rename(columns={'index': 'case'}).to_dict('records'),
                         'NWP': res['val'][s]['NWP가중'], 'ens': res['val'][s]['앙상블가중'],
                         'bias': res['val'][s]['수준보정'], 'cqr': res['val'][s]['CQR']},
                 'imp': {k: round(float(v), 2) for k, v in res['imp'][s].items()}}
    with open(os.path.join(out_dir, f'RE_forecast_{tag}.json'), 'w', encoding='utf-8') as fh:
        json.dump(js, fh, ensure_ascii=False, default=float)
    return xlsx, mt, daily


def main():
    ap = argparse.ArgumentParser(description='신재생(태양광·풍력) 발전량 AI 예측 툴')
    ap.add_argument('--data-dir', required=True)
    ap.add_argument('--start', default='2026-10-17')
    ap.add_argument('--end', default='2026-10-23')
    ap.add_argument('--train-years', type=int, default=3)
    ap.add_argument('--out', default='outputs')
    a = ap.parse_args()
    res, out = run(a.data_dir, a.start, a.end, a.train_years, a.out)
    pd.to_pickle((res, out), os.path.join(a.out, 'last_run.pkl'))
    tag = f"{a.start.replace('-', '')}-{a.end.replace('-', '')}"
    xlsx, mt, daily = save_outputs(res, out, a.out, tag)
    for s in SOURCES:
        log(f'\n=== {SRC_KO[s]} 사전검증 ===')
        log(res['val'][s]['table'][['MAE(MWh)', 'nMAE(%,용량)', 'MBE(MWh)', '총량오차(%)', 'R2']].round(3).to_string())
        if s in mt:
            log(f'=== {SRC_KO[s]} 예측기간 비교 ===')
            log(mt[s][['MAE(MWh)', 'RMSE(MWh)', 'nMAE(%,용량)', 'MBE(MWh)', '총량오차(%)', 'R2', 'P10~P90적중(%)']].round(3).to_string())
            log((daily[s][['실측', 'AI예측(P50)', 'AI_D-1롤링', '기준_전일지속', '사후_관측기상']]).round(2).to_string())
    log(f'\n저장: {xlsx}')


if __name__ == '__main__':
    main()
