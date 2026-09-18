%% Trace des performances DDS : SFDR et SNR
clear; clc; close all;

% 1. Definition des axes
segments = [4, 8, 16, 32];
accum_bits = [16, 18, 20, 22, 24];

% 2. Insertion des donnees (Lignes = Accumulateurs, Colonnes = Segments)
% Ordre des lignes : 16 bits, 18 bits, 20 bits, 22 bits, 24 bits
sfdr_data = [
    58.10, 71.87, 80.49, 78.12; % 16 bits
    57.46, 67.71, 79.46, 80.12; % 18 bits
    57.50, 67.71, 80.81, 80.19; % 20 bits
    57.52, 67.94, 80.60, 79.96; % 22 bits
    57.51, 67.98, 80.74, 79.97  % 24 bits
];

snr_data = [
    52.90, 64.19, 70.90, 72.38; % 16 bits
    52.18, 62.46, 72.61, 73.23; % 18 bits
    52.18, 62.44, 72.64, 73.23; % 20 bits
    52.19, 62.38, 72.60, 73.24; % 22 bits
    52.19, 62.38, 72.60, 73.21  % 24 bits
];

% Palette de couleurs lisible pour les 5 courbes
couleurs = lines(5); 
marqueurs = {'o', 's', '^', 'd', 'v'};

%% 3. Premier graphique : Evolution du SFDR
fig1 = figure('Name', 'Evolution du SFDR', 'Color', 'w', 'Position', [100, 100, 800, 500]);
hold on;
for i = 1:length(accum_bits)
    plot(segments, sfdr_data(i, :), ['-', marqueurs{i}], ...
        'Color', couleurs(i,:), 'LineWidth', 2, 'MarkerSize', 8, 'MarkerFaceColor', couleurs(i,:));
end
hold off;

% Mise en forme
title('Evolution du SFDR en fonction du nombre de segments ROM', 'FontSize', 14);
xlabel('Nombre de segments (Partitionnement)', 'FontSize', 12);
ylabel('SFDR (dBc)', 'FontSize', 12);
xticks(segments); % Force l'affichage precis des abscisses (4, 8, 16, 32)
grid on;
legend(strcat(string(accum_bits), ' bits'), 'Location', 'best', 'FontSize', 11);
set(gca, 'FontSize', 12, 'LineWidth', 1);

%% 4. Second graphique : Evolution du SNR
fig2 = figure('Name', 'Evolution du SNR', 'Color', 'w', 'Position', [150, 150, 800, 500]);
hold on;
for i = 1:length(accum_bits)
    plot(segments, snr_data(i, :), ['-', marqueurs{i}], ...
        'Color', couleurs(i,:), 'LineWidth', 2, 'MarkerSize', 8, 'MarkerFaceColor', couleurs(i,:));
end
hold off;

% Mise en forme
title('Evolution du SNR en fonction du nombre de segments ROM', 'FontSize', 14);
xlabel('Nombre de segments (Partitionnement)', 'FontSize', 12);
ylabel('SNR (dB)', 'FontSize', 12);
xticks(segments); 
grid on;
legend(strcat(string(accum_bits), ' bits'), 'Location', 'best', 'FontSize', 11);
set(gca, 'FontSize', 12, 'LineWidth', 1);

%% 5. Sauvegarde des courbes en format vectoriel PDF
% Forcer MATLAB a rafraichir tous les affichages graphiques en memoire
drawnow;

% Export de la figure du SFDR
exportgraphics(fig1, 'evolution_sfdr.pdf', 'ContentType', 'vector');

% Export de la figure du SNR
exportgraphics(fig2, 'evolution_snr.pdf', 'ContentType', 'vector');