%% Comparison: Chisel Output vs Pure Sine (Reference LUT)
clear; clc; close all;

%% 1. Circuit Parameters Definition (Aligned with configDeBase)
accumWidth = 16;       % 18 bits
phaseOutWidth = 14;    % 15 bits
ampWidth   = 12;       
fcw        = 655;      % Exact simulation value
f_ech      = 100e6;    
max_amp = (2^(ampWidth - 1)) - 1; 

%% 2. Load Hardware Data (Chisel)
try
    y_chisel = load('sin_dds_output_odatix.txt'); 
catch
    error('File sin_dds_output_odatix.txt not found or badly formatted.');
end
y_chisel = y_chisel(:); 
idx_neg = y_chisel > 2047;
y_chisel(idx_neg) = y_chisel(idx_neg) - 4096;
N_pts = length(y_chisel);

%% 3. Generate Reference Sine (Exact reproduction of original script)
Nacc = accumWidth;
Nrom = Nacc - 2;
LUT_SIZE = 2^Nrom;
% Exact quantization step
q = 2 / (2^ampWidth); 
% ROM creation (Normalized quarter-wave between -1 and 1)
addr = 0:LUT_SIZE-1;
ROM = sin((pi/2) * (addr + 0.5) / LUT_SIZE);
% Masks adapted to accumulator size (uint32 required)
phase = uint32(0);
phase_mask = uint32(2^Nacc - 1);
quad_mask  = uint32(3);
rom_mask   = uint32(2^Nrom - 1);
y = zeros(N_pts, 1);
phase_hist = zeros(N_pts, 1, 'uint32');
quadrant_hist = zeros(N_pts, 1, 'uint32');
rom_addr_hist = zeros(N_pts, 1, 'uint32');
for n = 1:N_pts
    % Increment modulo 2^Nacc
    phase = bitand(phase + uint32(fcw), phase_mask);
    phase_hist(n) = phase;
    % The 2 MSBs define the quadrant
    quadrant = bitshift(phase, -(Nacc - 2));
    quadrant = bitand(quadrant, quad_mask);
    quadrant_hist(n) = quadrant;
    % Remaining bits define position within the quadrant
    offset = bitand(phase, rom_mask);
    % Symmetry folding to use only a quarter sine wave
    if bitand(quadrant, 1) == 0
        rom_addr = offset;
    else
        rom_addr = bitxor(offset, rom_mask);
    end
    rom_addr_hist(n) = rom_addr;
    % Sign based on quadrant
    if quadrant < 2
        sign_val = 1;
    else
        sign_val = -1;
    end
    % Normalized ROM read
    y(n) = sign_val * ROM(double(rom_addr) + 1);
end

% DITHERING STEP:
% 1. Set random seed so SFDR is stable at each execution
rng('default'); 
% 2. Add Gaussian white noise before quantization
y_sinus_pur = round((y + 0.00001 * randn(size(y))) / q);

%% 4. Surgical Temporal Alignment 
nb_points_ignores = 20; 
y_chisel_propre = y_chisel(nb_points_ignores + 1 : end);
decalage = finddelay(y_sinus_pur(1:1000), y_chisel_propre(1:1000)); 
fprintf('Ignored transient points: %d\n', nb_points_ignores);
fprintf('Calculated hardware offset: %d samples\n', decalage);
if decalage > 0
    y_chisel_aligne = y_chisel_propre(decalage + 1 : end);
    y_sinus_pur = y_sinus_pur(1 : end - decalage);
elseif decalage < 0
    y_chisel_aligne = y_chisel_propre(1 : end + decalage);
    y_sinus_pur = y_sinus_pur(-decalage + 1 : end);
else
    y_chisel_aligne = y_chisel_propre;
end
taille_min = min(length(y_chisel_aligne), length(y_sinus_pur));
y_chisel_aligne = y_chisel_aligne(1:taille_min);
y_sinus_pur = y_sinus_pur(1:taille_min);
erreur = y_chisel_aligne - y_sinus_pur;
rmse_err = sqrt(mean(erreur.^2));
max_err = max(abs(erreur));
fprintf('\n--- Error Analysis ---\n');
fprintf('RMSE (Chisel vs Reference LUT): %.4f LSB\n', rmse_err);
fprintf('Max Error                     : %.0f LSB\n', max_err);

%% 5. Dynamic Temporal Graphical Display
figure('Name', 'Comparative Analysis - Chisel vs Reference LUT', 'Color', 'w', 'Position', [100, 100, 1000, 700]);
subplot(2, 1, 1);
start_pt = min(30000, max(1, taille_min - 500));
pts = start_pt:min(start_pt + 500, taille_min);   
plot(pts, y_sinus_pur(pts), 'g-', 'LineWidth', 2); hold on;
plot(pts, y_chisel_aligne(pts), 'r:', 'LineWidth', 1.5);
title('Temporal Superposition (Zoom on 500 points)');
xlabel('Samples'); ylabel('Amplitude (LSB)');
legend('Reference LUT (MATLAB)', 'Chisel Output (Quantized)', 'Location', 'best');
grid on;

subplot(2, 1, 2);
plot(erreur, 'r', 'LineWidth', 1);
title(sprintf('Chisel Hardware Error | RMSE = %.2f LSB | Max = %.0f LSB', rmse_err, max_err));
xlabel('Global samples'); ylabel('Error (LSB)');
grid on;

%% 6. Calculation and Display of SFDR and SNR (Chisel Output Only)
fprintf('\n--- Spectral Analysis (SFDR & SNR) ---\n');
sfdr_pur    = sfdr(y_sinus_pur, f_ech);
sfdr_chisel = sfdr(y_chisel_aligne, f_ech);
snr_pur    = snr(y_sinus_pur, f_ech);
snr_chisel = snr(y_chisel_aligne, f_ech);
fprintf('SFDR Reference LUT         : %.2f dBc\n', sfdr_pur);
fprintf('SFDR Hardware (Chisel)     : %.2f dBc\n', sfdr_chisel);
fprintf('SNR Reference LUT          : %.2f dB\n', snr_pur);
fprintf('SNR Hardware (Chisel)      : %.2f dB\n', snr_chisel);

figure('Name', 'Spectral Analysis - Chisel Output', 'Color', 'w', 'Position', [150, 150, 800, 500]);
sfdr(y_chisel_aligne, f_ech);
title(sprintf('Chisel Output Spectrum\nSFDR = %.2f dBc | SNR = %.2f dB', sfdr_chisel, snr_chisel));

% Force MATLAB to update the bounding box BEFORE export
drawnow;
exportgraphics(gcf, 'spectral_analysis_chisel_EightWave_R2.pdf', 'ContentType', 'vector');