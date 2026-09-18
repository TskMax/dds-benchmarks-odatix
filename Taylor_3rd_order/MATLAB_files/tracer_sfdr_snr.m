%% Trace des performances DDS : SFDR et SNR
clear; clc; close all;

% 1. Definition des axes
segments = [4, 8, 16, 32];
accum_bits = [16, 18, 20, 22, 24];

% 2. Insertion des donnees (Lignes = Accumulateurs, Colonnes = Segments)
% Ordre des lignes : 16 bits, 18 bits, 20 bits, 22 bits, 24 bits
sfdr_data = [
    84.82, 80.55, 82.55, 78.63; % 16 bits
    81.45, 79.20, 78.38, 79.63; % 18 bits
    80.32, 80.32, 80.19, 80.50; % 20 bits
    81.18, 80.71, 80.74, 80.71; % 22 bits
    81.22, 80.70, 80.81, 80.91  % 24 bits
];

snr_data = [
    71.78, 72.72, 72.25, 72.67; % 16 bits
    71.91, 73.18, 73.35, 73.27; % 18 bits
    73.20, 73.20, 73.25, 73.23; % 20 bits
    71.60, 73.25, 73.20, 73.25; % 22 bits
    71.56, 73.27, 73.20, 73.19  % 24 bits
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