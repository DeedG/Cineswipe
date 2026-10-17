# CineSwipe — Fiche Play Store (brouillon)

> Décision du 17 octobre 2026 : pas de Play Store pour l'instant (compte développeur payant). L'appli est distribuée par les releases du dépôt public GitHub, avec la vérification des mises à jour intégrée. Ce brouillon reste prêt si ça change.

## Textes

**Nom** : CineSwipe — films et séries

**Description courte** (80 caractères max) :
Tes films et séries vus, des recos qui te ressemblent, un classement en duels.

**Description complète** :

CineSwipe t'aide à garder la trace de tout ce que tu as vu, à le classer et à trouver quoi regarder ensuite. Films et séries, dans deux mondes séparés.

• Recos qui apprennent de tes gestes : glisse à gauche si tu l'as vu, à droite pour le garder pour plus tard.
• Recensement : retrouve en quelques minutes tous les films et séries que tu as déjà vus.
• L'Arène : deux titres, tu choisis ton préféré ; ton classement et tes étoiles se calculent tout seuls.
• Séries : où tu en es (en cours avec la saison, finie, par épisodes, décrochée), et une notification quand une nouvelle saison arrive.
• Ce soir : que regarder parmi ce que tu as sous la main, séries en cours d'abord.
• Entre amis : montre ton QR code, ton ami le scanne, et découvrez votre compatibilité et quoi voir ensemble.
• Stats, rétro de l'année à partager, listes perso, widgets.
• Homelab (facultatif) : Plex, Jellyfin, Seerr, Radarr, Sonarr — dispo sur ton serveur, demandes d'un swipe, saison par saison.
• Import depuis Letterboxd, Trakt, IMDb ou un CSV ; sauvegarde automatique.

Sans compte, sans publicité, sans traceur : tes données restent sur ton téléphone. Application libre (GPL v3).

Ce produit utilise l'API TMDB mais n'est ni approuvé ni certifié par TMDB.

## Sécurité des données (formulaire Play Console)

- Données collectées : **aucune** (rien n'est envoyé au développeur).
- Données partagées : **aucune**. Les requêtes à TMDB ne contiennent que les titres recherchés ; les échanges avec le homelab vont vers les serveurs de l'utilisateur.
- Chiffrement en transit : oui (HTTPS vers TMDB ; homelab selon l'adresse saisie par l'utilisateur).
- Suppression des données : dans l'appli (Réglages › Mes données › Tout effacer) ou en la désinstallant.
- Autorisations : Internet ; notifications (film vu sur le serveur, nouvelle saison) ; caméra (scanner le QR code d'un ami, facultatif).

## Classification du contenu

Application de catalogue et de recommandations : affiches et synopsis viennent de TMDB (contenus pour adultes exclus des recherches). Pas de contenu généré par les utilisateurs partagé publiquement, pas d'achats.

## Reste à faire avant l'envoi

1. Créer une clé TMDB dédiée à l'appli et l'ajouter en secret `TMDB_BUILTIN_KEY` (dépôt privé et dépôt public) : sans elle, chaque utilisateur doit créer la sienne.
2. Compte développeur Google Play (25 $, une seule fois).
3. Envoyer le fichier `.aab` publié avec chaque version (même clé de signature, qui sert de clé d'envoi ; activer la signature d'application par Google Play).
4. Politique de confidentialité en ligne : `docs/confidentialite.md` du dépôt public (lien GitHub).
5. Captures d'écran : le workflow « Captures » les produit (téléphone, pliant) ; image de présentation 1024 × 500 à faire.
6. Traduction anglaise (facultative au départ : la fiche peut n'être qu'en français).
7. Test fermé : Google demande désormais 12 testeurs pendant 14 jours pour un nouveau compte personnel.
