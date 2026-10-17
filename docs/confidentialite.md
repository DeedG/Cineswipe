# CineSwipe — Politique de confidentialité

*Dernière mise à jour : 17 octobre 2026*

CineSwipe est une application libre (licence GPL v3), sans compte, sans publicité et sans traceur.

## Ce qui reste sur ton téléphone

Tes films et séries vus, tes notes, tes duels, tes listes, tes réponses au recensement et tes réglages sont enregistrés **uniquement sur ton téléphone**. Ils ne sont envoyés à aucun serveur de CineSwipe : il n'y en a pas.

Les clés et mots de passe de tes services (clé TMDB personnelle, Plex, Jellyfin, Seerr, Radarr, Sonarr) sont chiffrés sur le téléphone avec le Keystore d'Android.

## Ce qui part sur Internet, et vers qui

- **TMDB (The Movie Database)** : pour chercher des films et séries, afficher leurs fiches, affiches et recommandations, l'application interroge l'API de TMDB. Ces requêtes contiennent les identifiants ou les titres recherchés, pas tes données personnelles. Voir la [politique de confidentialité de TMDB](https://www.themoviedb.org/privacy-policy).
- **YouTube** : la bande-annonce s'ouvre dans YouTube ou le navigateur, seulement quand tu la demandes.
- **Ton homelab** (facultatif) : si tu configures Plex, Jellyfin, Seerr, Radarr ou Sonarr, l'application communique directement avec **tes** serveurs, aux adresses que tu as saisies.

## Comparer ses goûts entre amis

Le QR code (ou le code partagé) contient ton prénom si tu l'as saisi, une sélection de titres vus avec tes étoiles et ta liste À voir. Il n'est transmis qu'à la personne qui le scanne ou à qui tu l'envoies toi-même ; la comparaison se fait sur son téléphone. La caméra ne sert qu'à lire ce code, l'image n'est ni enregistrée ni envoyée.

## Sauvegardes et exports

Les sauvegardes automatiques et les exports sont écrits dans le dossier que tu choisis (téléphone, carte SD, dossier synchronisé). Tu en gardes le contrôle.

## Notifications

Les notifications (film vu sur ton serveur, nouvelle saison) sont calculées sur le téléphone.

## Rapports de plantage

En cas de plantage, un rapport est gardé sur le téléphone et n'est partagé que si tu l'envoies toi-même depuis les réglages.

## Effacer tes données

Réglages › Mes données › Tout effacer, ou désinstaller l'application.

## Contact

Questions : ouvrir un ticket sur [github.com/DeedG/Cineswipe](https://github.com/DeedG/Cineswipe/issues).

*Ce produit utilise l'API TMDB mais n'est ni approuvé ni certifié par TMDB.*
