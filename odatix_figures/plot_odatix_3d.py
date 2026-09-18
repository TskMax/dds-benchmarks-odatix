import os
import yaml
import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
from matplotlib import cm

# ==========================================
# 1. PARAMÈTRES DU SCRIPT
# ==========================================
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
# Les architectures que l'on veut comparer sur l'axe Y
ARCHITECTURES = ['FullWave', 'QuarterWave', 'EightWave']

# Pour une vue 3D lisible, il vaut mieux fixer la technologie (la tension).
# Tu peux changer cette valeur pour 0V9 ou 1V0.
TECHNOLOGIE_CIBLE = 'TSMC_N28_HPCplus_P140_12T_0V8'

# Configuration visuelle
plt.rcParams.update({'font.size': 10, 'axes.labelsize': 11})

# ==========================================
# 2. EXTRACTION DE TOUTES LES ARCHITECTURES
# ==========================================
def charger_donnees_combinees():
    lignes = []
    unites = {}
    
    for arch in ARCHITECTURES:
        chemin_yaml = os.path.join(SCRIPT_DIR, arch, 'results_design_compiler.yml')
        
        if not os.path.exists(chemin_yaml):
            print(f"⚠️  Attention: Fichier introuvable pour {arch} ({chemin_yaml})")
            continue
            
        with open(chemin_yaml, 'r') as file:
            data = yaml.safe_load(file)
            
        if not unites:
            unites = data.get('units', {})
            
        resultats_fmax = data.get('fmax_synthesis', {})
        
        # Filtrer uniquement la technologie ciblée
        if TECHNOLOGIE_CIBLE in resultats_fmax:
            archs_data = resultats_fmax[TECHNOLOGIE_CIBLE]
            
            # Récupérer les données de l'architecture courante
            # (On cherche soit FullWave, soit QuarterWave, etc. dans le YAML)
            for nom_arch_yaml, bits_dict in archs_data.items():
                # On s'assure qu'on lit bien les données de l'architecture qu'on visite
                if nom_arch_yaml != arch:
                    continue 
                    
                for bit_str, metrics in bits_dict.items():
                    if not metrics: 
                        continue
                    
                    bit_val = int(bit_str.replace('Bits', ''))
                    
                    lignes.append({
                        'Architecture': arch,
                        'Resolution_Bits': bit_val,
                        'Fmax': metrics.get('Fmax'),
                        'Cell_Area': metrics.get('Cell_Area'),
                        'Total_Power': metrics.get('Total_Power')
                    })
                    
    df = pd.DataFrame(lignes)
    return df, unites

# ==========================================
# 3. FONCTION DE TRACÉ 3D (SURFACE)
# ==========================================
def tracer_surface_3d(df, metrique_z, label_z, titre, output_dir):
    # On pivote le tableau pour avoir un format de "Grille" (Mesh)
    # Lignes = Architectures, Colonnes = Resolutions, Valeurs = Metrique Z
    pivot_df = df.pivot(index='Architecture', columns='Resolution_Bits', values=metrique_z)
    
    # On force l'ordre logique sur l'axe Y : de la plus grosse archi à la plus optimisée
    pivot_df = pivot_df.reindex(ARCHITECTURES)
    
    # On supprime les colonnes qui auraient des trous (NaN) pour que la surface soit continue
    pivot_df = pivot_df.dropna(axis=1)

    if pivot_df.empty:
        print(f"Pas assez de données communes pour tracer {metrique_z}")
        return

    # Préparation des axes mathématiques
    x_vals = pivot_df.columns.values          # Ex: [8, 10, 12, 14, 16]
    y_vals = np.arange(len(ARCHITECTURES))    # Ex: [0, 1, 2] (Numérique obligatoire pour la 3D)
    
    # Création du maillage 2D (Meshgrid)
    X, Y = np.meshgrid(x_vals, y_vals)
    Z = pivot_df.values

    # Création de la figure 3D
    fig = plt.figure(figsize=(10, 7))
    ax = fig.add_subplot(111, projection='3d')
    
    # Tracé de la surface (cmap permet d'avoir un joli dégradé de couleurs selon la hauteur)
    surf = ax.plot_surface(X, Y, Z, cmap=cm.viridis, edgecolor='k', alpha=0.85)

    # Configuration des étiquettes
    ax.set_title(titre, pad=20, fontweight='bold')
    
    ax.set_xlabel('Résolution (Bits)', labelpad=10)
    ax.set_xticks(x_vals)
    
    ax.set_ylabel('Architecture', labelpad=10)
    ax.set_yticks(y_vals)
    ax.set_yticklabels(ARCHITECTURES) # Remplace les 0,1,2 par les vrais noms
    
    ax.set_zlabel(label_z, labelpad=10)
    
    # Ajout d'une barre de couleur pour lire les valeurs facilement
    fig.colorbar(surf, ax=ax, shrink=0.5, aspect=10, pad=0.1)

    # Orientation par défaut de la caméra
    ax.view_init(elev=25, azim=135)

    plt.tight_layout()
    
    # Sauvegarde
    os.makedirs(output_dir, exist_ok=True)
    chemin_pdf = os.path.join(output_dir, f'3D_Surface_{metrique_z}.pdf')
    plt.savefig(chemin_pdf, dpi=300, format='pdf')
    
    # Affichage interactif
    print(f"Graphique 3D généré : {chemin_pdf}")
    plt.show()

# ==========================================
# 4. EXÉCUTION PRINCIPALE
# ==========================================
if __name__ == "__main__":
    print("Analyse inter-architectures en cours...")
    df_combine, unites = charger_donnees_combinees()
    
    if df_combine.empty:
        print("Aucune donnée trouvée. Vérifie l'arborescence de tes dossiers.")
        exit()

    dossier_sortie = os.path.join(SCRIPT_DIR, 'Comparaison_3D')
    
    # 1. Surface Fmax
    tracer_surface_3d(
        df=df_combine,
        metrique_z='Fmax',
        label_z=f"Fmax ({unites.get('Fmax', 'MHz')})",
        titre=f"Comparatif Fmax\n({TECHNOLOGIE_CIBLE})",
        output_dir=dossier_sortie
    )
    
    # 2. Surface Occupation Silicium
    tracer_surface_3d(
        df=df_combine,
        metrique_z='Cell_Area',
        label_z=f"Surface ({unites.get('Cell_Area', 'um^2')})",
        titre=f"Comparatif Empreinte Silicium\n({TECHNOLOGIE_CIBLE})",
        output_dir=dossier_sortie
    )

    # 3. Surface Puissance Totale
    tracer_surface_3d(
        df=df_combine,
        metrique_z='Total_Power',
        label_z=f"Puissance ({unites.get('Total_Power', 'mW')})",
        titre=f"Comparatif Consommation\n({TECHNOLOGIE_CIBLE})",
        output_dir=dossier_sortie
    )