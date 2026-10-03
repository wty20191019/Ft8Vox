/**
 * @file ft8_gfsk.c
 * @brief GFSK 脉冲与复相位参考生成实现。
 */
#include "ft8_gfsk.h"

#include <math.h>
#include <stdlib.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define GFSK_CONST_K 5.336446f /* == pi * sqrt(2 / log(2)) */

void ft8_gfsk_pulse(int n_spsym, float symbol_bt, float* pulse)
{
    for (int i = 0; i < 3 * n_spsym; ++i)
    {
        float t = i / (float)n_spsym - 1.5f;
        float arg1 = GFSK_CONST_K * symbol_bt * (t + 0.5f);
        float arg2 = GFSK_CONST_K * symbol_bt * (t - 0.5f);
        pulse[i] = (erff(arg1) - erff(arg2)) / 2;
    }
}

int ft8_gfsk_reference(const uint8_t* tones, int n_sym, float f0, float symbol_bt,
                       float symbol_period, int signal_rate, float* re, float* im)
{
    if (!tones || !re || !im || n_sym <= 0 || signal_rate <= 0)
        return 0;

    int n_spsym = (int)(0.5f + signal_rate * symbol_period);
    if (n_spsym <= 0)
        return 0;
    int n_wave = n_sym * n_spsym;
    float hmod = 1.0f;
    float dphi_peak = 2 * (float)M_PI * hmod / n_spsym;

    float* dphi = (float*)malloc((size_t)(n_wave + 2 * n_spsym) * sizeof(float));
    float* pulse = (float*)malloc((size_t)(3 * n_spsym) * sizeof(float));
    if (!dphi || !pulse)
    {
        free(dphi);
        free(pulse);
        return 0;
    }

    for (int i = 0; i < n_wave + 2 * n_spsym; ++i)
        dphi[i] = 2 * (float)M_PI * f0 / signal_rate;

    ft8_gfsk_pulse(n_spsym, symbol_bt, pulse);

    for (int i = 0; i < n_sym; ++i)
    {
        int ib = i * n_spsym;
        for (int j = 0; j < 3 * n_spsym; ++j)
            dphi[j + ib] += dphi_peak * tones[i] * pulse[j];
    }

    /* 首尾补两个假符号，使相位平滑（与编码端一致） */
    for (int j = 0; j < 2 * n_spsym; ++j)
    {
        dphi[j] += dphi_peak * pulse[j + n_spsym] * tones[0];
        dphi[j + n_sym * n_spsym] += dphi_peak * pulse[j] * tones[n_sym - 1];
    }

    float phi = 0;
    for (int k = 0; k < n_wave; ++k)
    {
        re[k] = cosf(phi);
        im[k] = sinf(phi);
        phi = fmodf(phi + dphi[k + n_spsym], 2 * (float)M_PI);
    }

    free(dphi);
    free(pulse);
    return n_wave;
}
