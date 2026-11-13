package fr.paybridge.mas.adapters.inbound.presentation;

/** Géométrie en unités logiques : les bornes fournies excluent la barre des tâches. */
public record WindowPlacement(double x,double y,double width,double height,double minWidth,double minHeight) {
    public static WindowPlacement fit(double left,double top,double availableWidth,double availableHeight){
        if(!Double.isFinite(left)||!Double.isFinite(top)||!Double.isFinite(availableWidth)||!Double.isFinite(availableHeight)||availableWidth<=0||availableHeight<=0){
            throw new IllegalArgumentException("Zone d'écran invalide");
        }
        double width=Math.min(1220,availableWidth*.94),height=Math.min(900,availableHeight*.92);
        return new WindowPlacement(left+(availableWidth-width)/2,top+(availableHeight-height)/2,width,height,Math.min(760,width),Math.min(480,height));
    }
}
