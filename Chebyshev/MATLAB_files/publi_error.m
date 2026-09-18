%% Comparison: Chisel Output vs Pure Sine (Reference LUT)
clear; clc; close all;

%% 1. Circuit Parameters Definition
accumWidth    = 17;       % 17 bits accumulator
phaseOutWidth = 14;       % 14 bits phase 
ampWidth      = 12;       % 12 bits amplitude
fcw           = 1312;     % Exact simulation value
f_ech         = 100e6;    

%% 2. Load Hardware Data (Chisel) - Robust 12-bit Parsing
try
    y_chisel_raw = load('sin_dds_output_base.txt'); 
catch
    error('File sin_dds_output_base.txt not found or badly formatted.');
end

% 1. Restriction stricte sur 12 bits (modulo 4096)
y_chisel = mod(y_chisel_raw, 2^ampWidth);

% 2. Reconstitution du complement a 2 
idx_neg = y_chisel >= (2^(ampWidth - 1));
y_chisel(idx_neg) = y_chisel(idx_neg) - (2^ampWidth);

%% 3 & 4. Extraction I/Q et Alignement Parfait (Standard IEEE)
% On ignore les premiers echantillons (Reset + Latence du pipeline)
nb_points_ignores = 50; 
y_chisel_propre = double(y_chisel(nb_points_ignores + 1 : end));
N_propre = length(y_chisel_propre);

% Base de temps
t = (0:N_propre-1)';

% Frequence angulaire theorique exacte generee par le DDS
f0 = fcw / (2^accumWidth); 
omega = 2 * pi * f0;

% Projection I/Q (Demodulation sur la fondamentale)
cos_basis = cos(omega * t);
sin_basis = sin(omega * t);

I = (2 / N_propre) * sum(y_chisel_propre .* cos_basis);
Q = (2 / N_propre) * sum(y_chisel_propre .* sin_basis);
DC = mean(y_chisel_propre);

% Reconstitution du sinus ideal rigoureusement cale en phase et amplitude
y_sinus_pur_aligne = I * cos_basis + Q * sin_basis + DC;

% Signaux prets pour l'analyse d'erreur
y_chisel_centre = y_chisel_propre;
erreur = y_chisel_centre - y_sinus_pur_aligne;

rmse_err = sqrt(mean(erreur.^2));
max_err = max(abs(erreur));

fprintf('\n--- Error Analysis ---\n');
fprintf('Ignored transient points: %d\n', nb_points_ignores);
fprintf('Amplitude I/Q extracted : %.2f LSB\n', sqrt(I^2 + Q^2));
fprintf('Removed DC Offset       : %.4f LSB\n', DC);
fprintf('RMSE (Chisel vs Ref)    : %.4f LSB\n', rmse_err);
fprintf('Max Error               : %.0f LSB\n', max_err);

%% 5. Dynamic Temporal Graphical Display
figure('Name', 'Comparative Analysis - Chisel vs Reference LUT', 'Color', 'w', 'Position', [100, 100, 1000, 700]);

subplot(2, 1, 1);
start_pt = min(30000, max(1, N_propre - 500));
pts = start_pt:min(start_pt + 500, N_propre);   
plot(pts, y_sinus_pur_aligne(pts), 'g-', 'LineWidth', 2); hold on;
plot(pts, y_chisel_centre(pts), 'r:', 'LineWidth', 1.5);
title('Temporal Superposition (Zoom on 500 points)');
xlabel('Samples'); ylabel('Amplitude (LSB)');
legend('Ideal Sine (I/Q Extracted)', 'Chisel Output', 'Location', 'best');
grid on;

subplot(2, 1, 2);
plot(erreur, 'r', 'LineWidth', 1);
title(sprintf('Chisel Hardware Error | RMSE = %.2f LSB | Max = %.0f LSB', rmse_err, max_err));
xlabel('Global samples'); ylabel('Error (LSB)');
grid on;

%% 6. Calculation and Display of SFDR and SNR
fprintf('\n--- Spectral Analysis (SFDR & SNR) ---\n');
sfdr_pur    = sfdr(y_sinus_pur_aligne, f_ech);
sfdr_chisel = sfdr(y_chisel_centre, f_ech);
snr_pur     = snr(y_sinus_pur_aligne, f_ech);
snr_chisel  = snr(y_chisel_centre, f_ech);

fprintf('SFDR Reference Sine        : %.2f dBc\n', sfdr_pur);
fprintf('SFDR Hardware (Chisel)     : %.2f dBc\n', sfdr_chisel);
fprintf('SNR Reference Sine         : %.2f dB\n', snr_pur);
fprintf('SNR Hardware (Chisel)      : %.2f dB\n', snr_chisel);

figure('Name', 'Spectral Analysis - Chisel Output', 'Color', 'w', 'Position', [150, 150, 800, 500]);
sfdr(y_chisel_centre, f_ech);
title(sprintf('Chisel Output Spectrum\nSFDR = %.2f dBc | SNR = %.2f dB', sfdr_chisel, snr_chisel))
ax = gca;
ax.Position = [0.12, 0.18, 0.82, 0.70]; 
drawnow;
exportgraphics(gcf, 'spectral_analysis_chisel_Chebyshev.pdf', 'ContentType', 'vector');