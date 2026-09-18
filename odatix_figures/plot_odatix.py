import argparse
import yaml
import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns
import os

# Se placer dans le dossier générique : cd /DDS
# Activer l'environnement virtuel : source venv/bin/activate
# Se placer dans le dossier parent : cd /trace_odatix
# Commande de compilation : python3 plot_odatix.py


# ==========================================
# 1. PARAMETRES DU SCRIPT
# ==========================================
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ARCHITECTURE = 'QuarterWave'  # FullWave QuarterWave EightWave

# Configuration visuelle globale (style Seaborn pour des graphiques propres)
sns.set_theme(style="whitegrid")
plt.rcParams.update({'font.size': 12, 'axes.labelsize': 14})

# ==========================================
# 2. EXTRACTION ET PARSING DU YAML
# ==========================================
def charger_donnees(chemin_fichier):
    with open(chemin_fichier, 'r') as file:
        data = yaml.safe_load(file)
    
    unites = data.get('units', {})
    resultats_fmax = data.get('fmax_synthesis', {})
    
    lignes = []
    
    # Parcours de l'arborescence : Techno -> Architecture -> Bits
    for techno, archs in resultats_fmax.items():
        for arch, bits_dict in archs.items():
            for bit_str, metrics in bits_dict.items():
                if not metrics: 
                    continue # Ignore les dictionnaires vides (ex: 18Bits: {})
                
                # Nettoyage : "10Bits" -> 10 (entier) pour pouvoir trier l'axe X
                bit_val = int(bit_str.replace('Bits', ''))
                
                # Création d'une ligne de tableau avec TOUTES les puissances
                ligne = {
                    'Technologie': techno,
                    'Architecture': arch,
                    'Resolution_Bits': bit_val,
                    'Fmax': metrics.get('Fmax'),
                    'Cell_Area': metrics.get('Cell_Area'),
                    'Internal_Power': metrics.get('Internal_Power'),
                    'Switching_Power': metrics.get('Switching_Power'),
                    'Leakage_Power': metrics.get('Leakage_Power'),
                    'Total_Power': metrics.get('Total_Power')
                }
                lignes.append(ligne)
                
    # Conversion en DataFrame Pandas et tri par resolution
    df = pd.DataFrame(lignes)
    df = df.sort_values(by='Resolution_Bits')

    # MODIFICATION : Conversion de l'aire de µm² à mm²
    if 'Cell_Area' in df.columns:
        df['Cell_Area'] = df['Cell_Area'] / 1_000_000.0
    
    # MODIFICATION : Remplacement de l'unité pour l'affichage graphique
    unites['Cell_Area'] = 'mm²'

    return df, unites

# ==========================================
# 3. FONCTION DE TRACE PARAMETRABLE
# ==========================================
def tracer_courbe(df, metrique_y, label_y, titre, output_dir, nom_arch):
    plt.figure(figsize=(10, 6))
    
    # Trace une courbe pour chaque technologie
    sns.lineplot(
        data=df, 
        x='Resolution_Bits', 
        y=metrique_y, 
        hue='Technologie', 
        marker='o', 
        linewidth=2,
        markersize=8
    )
    
    # Nettoyage des legendes (pour ne garder que la fin du nom de la techno)
    handles, labels = plt.gca().get_legend_handles_labels()
    labels_propres = [l.split('_')[-1] for l in labels] # Ex: garde juste "0V8"
    plt.legend(handles, labels_propres, title="Tension (Vdd)")
    
    plt.title(titre, pad=15, fontweight='bold')
    plt.xlabel('Largeur du bus de phase (Bits)')
    plt.ylabel(label_y)
    
    # Force l'axe X a afficher uniquement des entiers pairs
    plt.xticks(df['Resolution_Bits'].unique())
    
    plt.tight_layout()
    os.makedirs(output_dir, exist_ok=True)
    
    output_path = os.path.join(output_dir, f'{nom_arch}_{metrique_y}.pdf')
    
    plt.savefig(output_path, dpi=300, format='pdf')
    if 'agg' not in plt.get_backend().lower():
        plt.show()
    plt.close()

# ==========================================
# 4. EXECUTION PRINCIPALE
# ==========================================
def parse_args():
    parser = argparse.ArgumentParser(
        description='Trace et sauvegarde les résultats Odatix depuis un dossier d\'architecture.'
    )
    parser.add_argument(
        '-a', '--arch',
        default=DEFAULT_ARCHITECTURE,
        help='Nom du dossier d\'architecture (ex: FullWave, QuarterWave).'
    )
    return parser.parse_args()


if __name__ == "__main__":
    args = parse_args()
    architecture = args.arch
    arch_dir = os.path.join(SCRIPT_DIR, architecture)
    FICHIER_YAML = os.path.join(arch_dir, 'results_design_compiler.yml')

    if not os.path.isdir(arch_dir):
        raise FileNotFoundError(f"Dossier d'architecture introuvable : {arch_dir}")
    if not os.path.isfile(FICHIER_YAML):
        raise FileNotFoundError(f"Fichier YAML introuvable : {FICHIER_YAML}")

    print(f"Chargement des donnees Odatix pour l'architecture : {architecture}")
    df_resultats, unites = charger_donnees(FICHIER_YAML)
    
    print("\nApercu des donnees extraites :")
    print(df_resultats.head())
    
    output_dir = arch_dir
    
    # --- Generation des graphiques ---
    
    # 1. Trace de la Frequence Max (Fmax)
    unite_fmax = unites.get('Fmax', 'MHz')
    tracer_courbe(
        df=df_resultats, 
        metrique_y='Fmax', 
        label_y=f'Frequence Maximale Fmax ({unite_fmax})', 
        titre='Evolution du Fmax en fonction de la resolution',
        output_dir=output_dir,
        nom_arch=architecture
    )
    
    # 2. Trace de l'Occupation Silicium (Cell Area) - L'unité sera bien "mm²" maintenant
    unite_area = unites.get('Cell_Area', 'um^2')
    tracer_courbe(
        df=df_resultats, 
        metrique_y='Cell_Area', 
        label_y=f'Surface logique ({unite_area})', 
        titre='Empreinte silicium en fonction de la resolution',
        output_dir=output_dir,
        nom_arch=architecture
    )
    
    # 3. Trace de la Consommation Totale (Total Power)
    unite_total = unites.get('Total_Power', 'mW')
    tracer_courbe(
        df=df_resultats, 
        metrique_y='Total_Power', 
        label_y=f'Puissance Totale ({unite_total})', 
        titre='Consommation energetique totale en fonction de la resolution',
        output_dir=output_dir,
        nom_arch=architecture
    )

    # 4. Trace de la Consommation Interne (Internal Power)
    unite_internal = unites.get('Internal_Power', 'mW')
    tracer_courbe(
        df=df_resultats, 
        metrique_y='Internal_Power', 
        label_y=f'Puissance Interne ({unite_internal})', 
        titre='Puissance interne (dissipee dans les cellules)',
        output_dir=output_dir,
        nom_arch=architecture
    )

    # 5. Trace de la Puissance de Commutation (Switching Power)
    unite_switching = unites.get('Switching_Power', 'mW')
    tracer_courbe(
        df=df_resultats, 
        metrique_y='Switching_Power', 
        label_y=f'Puissance de Commutation ({unite_switching})', 
        titre='Puissance de commutation (dissipee dans les interconnexions)',
        output_dir=output_dir,
        nom_arch=architecture
    )

    # 6. Trace des Courants de Fuite (Leakage Power)
    unite_leakage = unites.get('Leakage_Power', 'pW') 
    tracer_courbe(
        df=df_resultats, 
        metrique_y='Leakage_Power', 
        label_y=f'Puissance de Fuite ({unite_leakage})', 
        titre='Puissance de fuite statique en fonction de la resolution',
        output_dir=output_dir,
        nom_arch=architecture
    )