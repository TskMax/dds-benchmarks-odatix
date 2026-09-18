clear; clc; close all;

% 1. Definition of the real phase interval [0, pi/4]
N_points = 1000;
x = linspace(0, pi/4, N_points)';

% 2. Calculation of the Sine path
y_sin = sin(x);

% Creation of the matrix with the exact equations from the Chisel code
% Column 1: x
% Column 2: 4x^3 - 3x
M_sin = [x, (4*x.^3 - 3*x)];

% Least squares resolution (finds the best s1 and s3)
coeff_sin = M_sin \ y_sin;
s1 = coeff_sin(1);
s3 = coeff_sin(2);

% 3. Calculation of the Cosine path
% Mathematical target (subtracting 1.0 because C0 handles it)
y_cos = cos(x) - 1.0;

% Creation of the matrix with the exact equations from the Chisel code
% Column 1: 2x^2
% Column 2: 8x^4 - 8x^2
M_cos = [(2*x.^2), (8*x.^4 - 8*x.^2)];

% Least squares resolution (finds the best c2 and c4)
coeff_cos = M_cos \ y_cos;
c2 = coeff_cos(1);
c4 = coeff_cos(2);

% 4. Conversion to Q17 format (Hardware fixed-point)
Q = 17;
s1_hw = round(s1 * (2^Q));
s3_hw = round(s3 * (2^Q));
c2_hw = round(c2 * (2^Q));
c4_hw = round(c4 * (2^Q));
c0_hw = round(1.0 * (2^Q));

% 5. Output for Chisel
fprintf('// Normalized values (Q17) to copy into the Chisel module:\n');
fprintf('val coeff_s1 = %d.S(18.W)\n', s1_hw);
fprintf('val coeff_s3 = %d.S(18.W)\n', s3_hw);
fprintf('val coeff_c0 = %d.S\n', c0_hw);
fprintf('val coeff_c2 = %d.S(18.W)\n', c2_hw);
fprintf('val coeff_c4 = %d.S(18.W)\n', c4_hw);
