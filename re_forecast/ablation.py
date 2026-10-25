# -*- coding: utf-8 -*-
"""
변수 제외(ablation) 시나리오 비교
  - 발전원마다 '전체 사용' + '변수 분류 하나씩 제외' 시나리오를 같은 절차로 학습·예측
    (사전검증으로 예보모델·앙상블 가중과 수준보정 결정 → 3년 재학습 → 예측)
  - 평가 ① 사전검증 2026-08-16~10-16 (전날 발표 예보 입력, 약 2개월)  ← 순위 판단의 주 기준
        ② 예측기간 10/17~10/23 (주간 일괄 예측, D-1 롤링)            ← 7일뿐이라 참고
  - '전체'는 난수 시드를 바꿔 반복 → 시나리오 간 차이가 시드 흔들림보다 큰지 확인
사용 예
  python ablation.py --data-dir ./data --out ./outputs
"""
import argparse
import json
import os
import time

import pandas as pd

import re_forecast_tool as T

NOISE_SEEDS = (42, 7, 2024)


def evaluate(C, s, R):
    cap = R['meta']['capMW']
    vt = R['val']['table']
    actual = C.Y5_all.reindex(C.fidx)[s]
    fc = R['fc']
    row = {}
    for key, m in (('검증_D-1', vt.loc['앙상블 D-1(보정)']), ('검증_관측기상', vt.loc['재분석기상(모델성능)']),
                   ('예측_주간일괄', T.metrics(actual, fc['AI예측(P50)'], cap)),
                   ('예측_D-1롤링', T.metrics(actual, fc['AI_D-1롤링'], cap))):
        row[f'{key}_nMAE'] = m['nMAE(%,용량)']
        row[f'{key}_MAE'] = m['MAE(MWh)']
        row[f'{key}_RMSE'] = m['RMSE(MWh)']
        row[f'{key}_총량오차'] = m['총량오차(%)']
    return row


def main():
    ap = argparse.ArgumentParser(description='변수 제외 시나리오 비교')
    ap.add_argument('--data-dir', required=True)
    ap.add_argument('--start', default='2026-10-17')
    ap.add_argument('--end', default='2026-10-23')
    ap.add_argument('--train-years', type=int, default=3)
    ap.add_argument('--out', default='outputs')
    ap.add_argument('--sources', default='solar,wind')
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    C = T.prepare(a.data_dir, a.start, a.end, a.train_years)
    rows, daily = [], []
    for s in a.sources.split(','):
        cols = C.F_arch[s].columns
        grp = pd.Series({c: T.feature_group(s, c) for c in cols})
        T.log(f'\n[{T.SRC_KO[s]}] 변수 분류별 입력 수: {grp.value_counts().to_dict()}')
        scen = [('전체', ())] + [(f'{g} 제외', (g,)) for g in T.GROUPS[s]]
        for name, drop in scen:
            seeds = NOISE_SEEDS if name == '전체' else NOISE_SEEDS[:1]
            for seed in seeds:
                T.SEED = seed
                t = time.time()
                R = T.model_source(C, s, drop=drop, quantiles=False)
                ev = evaluate(C, s, R)
                ev.update({'source': s, '시나리오': name, '제외분류': drop[0] if drop else '', 'seed': seed,
                           '입력수': int((~grp.isin(drop)).sum())})
                rows.append(ev)
                d = R['fc'][['AI예측(P50)', 'AI_D-1롤링']].copy()
                d['실측'] = C.Y5_all.reindex(C.fidx)[s]
                d = d.groupby((d.index - pd.Timedelta(minutes=1)).strftime('%Y-%m-%d')).sum(min_count=1) / 1000
                for day, r in d.iterrows():
                    daily.append({'source': s, '시나리오': name, 'seed': seed, 'day': day,
                                  '실측': r['실측'], '주간일괄': r['AI예측(P50)'], 'D-1롤링': r['AI_D-1롤링']})
                T.log(f'  {name:8s} seed={seed:<5d} 검증D-1 nMAE {ev["검증_D-1_nMAE"]:.3f}%  '
                      f'예측 nMAE {ev["예측_주간일괄_nMAE"]:.3f}%  D-1롤링 {ev["예측_D-1롤링_nMAE"]:.3f}%  ({time.time() - t:.0f}s)')
                pd.DataFrame(rows).to_csv(os.path.join(a.out, 'ablation_raw.csv'), index=False, encoding='utf-8-sig')
    T.SEED = 42

    raw = pd.DataFrame(rows)
    pd.DataFrame(daily).to_csv(os.path.join(a.out, 'ablation_daily.csv'), index=False, encoding='utf-8-sig')
    # 요약: 전체(시드 평균) 대비 변화
    keys = ['검증_D-1_nMAE', '검증_관측기상_nMAE', '예측_주간일괄_nMAE', '예측_D-1롤링_nMAE',
            '검증_D-1_총량오차', '예측_주간일괄_총량오차']
    out, noise = [], {}
    for s, g in raw.groupby('source', sort=False):
        base = g[g['시나리오'] == '전체'][keys]
        noise[s] = {k: float(base[k].max() - base[k].min()) for k in keys}
        bm = base.mean()
        for name, h in g.groupby('시나리오', sort=False):
            m = h[keys].mean()
            r = {'source': s, '시나리오': name, '입력수': int(h['입력수'].iloc[0])}
            for k in keys:
                r[k] = m[k]
                if 'nMAE' in k:
                    r[k + '_변화'] = m[k] - bm[k]
                    r[k + '_변화율'] = (m[k] / bm[k] - 1) * 100
            out.append(r)
    summ = pd.DataFrame(out)
    summ.to_csv(os.path.join(a.out, 'ablation_summary.csv'), index=False, encoding='utf-8-sig')
    with open(os.path.join(a.out, 'ablation_noise.json'), 'w', encoding='utf-8') as fh:
        json.dump(noise, fh, ensure_ascii=False, indent=1)
    with pd.ExcelWriter(os.path.join(a.out, 'RE_ablation.xlsx'), engine='openpyxl') as xw:
        summ.round(3).to_excel(xw, sheet_name='요약', index=False)
        raw.round(3).to_excel(xw, sheet_name='시나리오별(시드포함)', index=False)
        pd.DataFrame(daily).round(2).to_excel(xw, sheet_name='일별(GWh)', index=False)
        pd.DataFrame(noise).T.round(3).to_excel(xw, sheet_name='시드흔들림')
    T.log('\n' + summ[['source', '시나리오', '입력수', '검증_D-1_nMAE', '검증_D-1_nMAE_변화율',
                       '예측_주간일괄_nMAE', '예측_주간일괄_nMAE_변화율', '예측_D-1롤링_nMAE']].round(3).to_string())
    T.log(f'시드 흔들림(전체, 최대-최소): {json.dumps(noise, ensure_ascii=False)}')


if __name__ == '__main__':
    main()
